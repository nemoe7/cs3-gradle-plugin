package com.lagradost.cloudstream3.gradle.tasks

import com.lagradost.cloudstream3.gradle.getCloudstream
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.errors.MessageReceiverImpl
import com.android.build.gradle.options.SyncOptions.ErrorFormatMode
import com.android.builder.dexing.ClassFileInputs
import com.android.builder.dexing.DexArchiveBuilder
import com.android.builder.dexing.DexParameters
import com.android.builder.dexing.r8.ClassFileProviderFactory
import com.android.tools.r8.R8Command
import com.android.tools.r8.R8
import com.android.tools.r8.OutputMode
import com.google.common.io.Closer
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.slf4j.LoggerFactory
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.stream.Collectors
import kotlin.io.path.extension

abstract class CompileDexTask : DefaultTask() {
    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    val input: ConfigurableFileCollection = project.objects.fileCollection()

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:OutputFile
    abstract val pluginClassFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    val proguardFiles: ConfigurableFileCollection = project.objects.fileCollection()

    private val intermediates = project.layout.buildDirectory.get().asFile.resolve("intermediates")

    private val mappingFile = intermediates.resolve("r8-mapping.txt")

    private fun filterOutOtherBuildDirs(paths: Collection<Path>): List<Path> {
        val projectBuildDir = project.layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()

        return paths.filter { path ->
            val normalizedPath = path.toAbsolutePath().normalize()

            // Keep if path is NOT under any "build" directory other than our own build dir
            !(normalizedPath.toString().contains("${File.separator}build${File.separator}") &&
            !normalizedPath.startsWith(projectBuildDir))
        }
    }

    private fun getObfuscatedClassName(originalClassName: String): String? {
        if (!mappingFile.exists()) throw IllegalStateException(
            "Mapping file does not exist at ${mappingFile.absolutePath}."
        )

        val prefix = "$originalClassName -> "
        mappingFile.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith(prefix)) {
                    // Line format: originalClassName -> obfuscatedName:
                    return line.substringAfter(prefix).removeSuffix(":").trim()
                }
            }
        }
        logger.debug("No obfuscated name found for $originalClassName in ${mappingFile.absolutePath}")
        return null // Not found, maybe not obfuscated
    }

    @TaskAction
    fun compileDex() {
        val android = project.extensions.getByName("android") as BaseExtension

        val minSdk = android.defaultConfig.minSdk ?: 21

        val dexOutputDir = outputFile.get().asFile.parentFile

        val debugType = android.buildTypes.getByName("debug")
        val releaseType = android.buildTypes.getByName("release")

        val useR8 = debugType.isMinifyEnabled || releaseType.isMinifyEnabled

        Closer.create().use { closer ->
            val dexBuilder = DexArchiveBuilder.createD8DexBuilder(
                DexParameters(
                    minSdkVersion = minSdk,
                    debuggable = true,
                    dexPerClass = false,
                    withDesugaring = true, // Make all plugins work on lower android versions
                    desugarBootclasspath = ClassFileProviderFactory(android.bootClasspath.map(File::toPath))
                        .also { closer.register(it) },
                    desugarClasspath = ClassFileProviderFactory(emptyList<Path>()).also(closer::register),
                    coreLibDesugarConfig = null,
                    messageReceiver = MessageReceiverImpl(
                        ErrorFormatMode.HUMAN_READABLE,
                        LoggerFactory.getLogger(CompileDexTask::class.java)
                    ),
                    enableApiModeling = false // Unknown option, setting to false seems to work
                )
            )

            if (useR8) {
                // Print mapping file to determine obfuscated entry point
                val requiredRules = intermediates.resolve("r8-required-rules.pro").apply {
                    parentFile.mkdirs()
                    writeText("-printmapping ${mappingFile.absolutePath}\n")
                }

                val programFiles = input.files
                    .flatMap { file ->
                        if (file.isDirectory)
                            file.walkTopDown().filter { it.extension == "class" }.toList()
                        else listOf(file)
                    }
                    .map { it.toPath().normalize() }

                val compileKotlinTask = project.tasks.named("compileDebugKotlin").get() as KotlinCompile

                val kotlinLibPaths = compileKotlinTask.libraries.files
                    .map { it.toPath().normalize() }
                    .toSet()

                val bootClasspath = android.bootClasspath.map { it.toPath().normalize() }

                val libraryPaths = filterOutOtherBuildDirs(kotlinLibPaths + bootClasspath)

                val r8Command = R8Command.builder()
                    .addProgramFiles(programFiles) // Program input (.jar/.class)
                    .addLibraryFiles(libraryPaths)
                    .addProguardConfigurationFiles(proguardFiles.files.map(File::toPath)) // ProGuard/R8 rules
                    .addProguardConfigurationFiles(listOf(requiredRules.toPath()))
                    .setMinApiLevel(minSdk) // Required to target API features
                    .setOutput(dexOutputDir.toPath(), OutputMode.DexIndexed)
                    .build()

                try {
                    logger.lifecycle("Running R8 minification with output to ${dexOutputDir}")
                    R8.run(r8Command)
                    logger.lifecycle("R8 minification completed successfully.")

                    if (!mappingFile.exists()) throw IllegalStateException(
                        "R8 mapping file not found at ${mappingFile.absolutePath} after minification."
                    )
                } catch (e: Exception) {
                    logger.error("R8 minification failed!", e)
                    throw e // rethrow if you want the task to fail
                }
            }


            val fileStreams =
                input.map { input -> ClassFileInputs.fromPath(input.toPath()).use { it.entries { _, _ -> true } } }
                    .toTypedArray()

            Arrays.stream(fileStreams).flatMap { it }
                .use { classesInput ->
                    val files = classesInput.collect(Collectors.toList())

                    if (!useR8) dexBuilder.convert(
                        files.stream(),
                        dexOutputDir.toPath(),
                        null,
                    )

                    for (file in files) {
                        val reader = ClassReader(file.readAllBytes())

                        val classNode = ClassNode()
                        reader.accept(classNode, 0)

                        for (annotation in classNode.visibleAnnotations.orEmpty() + classNode.invisibleAnnotations.orEmpty()) {
                            if (annotation.desc == "Lcom/lagradost/cloudstream3/plugins/CloudstreamPlugin;") {
                                val cloudstream = project.extensions.getCloudstream()

                                require(cloudstream.pluginClassName == null) {
                                    "Only 1 active plugin class per project is supported"
                                }

                                val ogName = classNode.name.replace('/', '.')

                                val name = if (useR8) getObfuscatedClassName(ogName) ?: throw IllegalStateException(
                                        "Failed to find obfuscated name for $ogName"
                                    ) else ogName

                                cloudstream.pluginClassName = name
                                    .also {
                                        pluginClassFile.asFile.orNull?.writeText(it)
                                        ?: logger.warn("pluginClassFile not set, skipping write")
                                    }
                            }
                        }
                    }
                }
        }

        logger.lifecycle("Compiled dex to ${outputFile.get()}")
    }
}
