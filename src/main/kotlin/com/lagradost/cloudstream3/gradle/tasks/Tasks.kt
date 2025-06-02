package com.lagradost.cloudstream3.gradle.tasks

import com.lagradost.cloudstream3.gradle.getCloudstream
import com.lagradost.cloudstream3.gradle.makeManifest
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.tasks.ProcessLibraryManifest
import groovy.json.JsonBuilder
import groovy.json.JsonGenerator
import org.gradle.api.Project
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

const val TASK_GROUP = "cloudstream"

fun mergeJars(jars: List<File>, outputJar: File) {
  ZipOutputStream(outputJar.outputStream()).use { out ->
    val added = mutableSetOf<String>()

    jars.forEach { jar ->
      ZipInputStream(jar.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          if (!entry.isDirectory && added.add(entry.name)) {
            out.putNextEntry(entry)
            zip.copyTo(out)
            out.closeEntry()
          }
          entry = zip.nextEntry
        }
      }
    }
  }
}

fun Long.toReadableSize(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = this.toDouble()
    var index = 0
    while (size >= 1024 && index < units.lastIndex) {
        size /= 1024
        index++
    }
    return String.format("%.2f %s", size, units[index])
}

fun registerTasks(project: Project) {
    val extension = project.extensions.getCloudstream()
    val intermediates = project.buildDir.resolve("intermediates")

    if (project.rootProject.tasks.findByName("makePluginsJson") == null) {
        project.rootProject.tasks.register("makePluginsJson", MakePluginsJsonTask::class.java) {
            it.group = TASK_GROUP

            it.outputs.upToDateWhen { false }

            it.outputFile.set(it.project.buildDir.resolve("plugins.json"))
        }
    }

    project.tasks.register("genSources", GenSourcesTask::class.java) {
        it.group = TASK_GROUP
    }

    val pluginClassFile = intermediates.resolve("pluginClass")

    val compileDex = project.tasks.register("compileDex", CompileDexTask::class.java) {
        it.group = TASK_GROUP

        it.pluginClassFile.set(pluginClassFile)

        val kotlinTask = project.tasks.findByName("compileDebugKotlin") as KotlinCompile?
        if (kotlinTask != null) {
            it.dependsOn(kotlinTask)
            it.input.from(kotlinTask.destinationDirectory)
        }

        val android = project.extensions.getByName("android") as BaseExtension
        val debugType = android.buildTypes.getByName("debug")
        val releaseType = android.buildTypes.getByName("release")

        it.proguardFiles.from(
            project.provider {
                val filesToAdd = android.defaultConfig.proguardFiles +
                        debugType.proguardFiles +
                        releaseType.proguardFiles

                filesToAdd.filter {
                    it.exists() && it.isFile && (it.extension == "pro" || it.extension == "txt")
                }
            }
        )

        val dependencies = project.configurations.getByName("implementation").dependencies
            .withType(ProjectDependency::class.java)
            .map { it.dependencyProject.path }

        it.logger.info("Resolved subproject dependencies for ${project.name}: $dependencies")

        dependencies.forEach { dependencyPath ->
            project.rootProject.findProject(dependencyPath)?.let { dependencyProject ->
                (dependencyProject.tasks.findByName("compileDebugKotlin") as? KotlinCompile)
                    ?.let { dependencyKotlinTask ->
                        it.dependsOn(dependencyKotlinTask)
                        it.input.from(dependencyKotlinTask.destinationDirectory)
                    } ?: run {
                        project.logger.warn("Could not find compileDebugKotlin task for dependency: $dependencyPath")
                    }
            } ?: run {
                project.logger.warn("Could not find project for dependency: $dependencyPath")
            }
        }

        // This task does not seem to be required for a successful cs3 file

//        val javacTask = project.tasks.findByName("compileDebugJavaWithJavac") as AbstractCompile?
//        if (javacTask != null) {
//            it.dependsOn(javacTask)
//            it.input.from(javacTask.destinationDirectory)
//        }

        it.outputFile.set(intermediates.resolve("classes.dex"))
    }

    val compileResources =
        project.tasks.register("compileResources", CompileResourcesTask::class.java) {
            it.group = TASK_GROUP

            val processManifestTask =
                project.tasks.getByName("processDebugManifest") as ProcessLibraryManifest
            it.dependsOn(processManifestTask)

            val android = project.extensions.getByName("android") as BaseExtension
            it.input.set(android.sourceSets.getByName("main").res.srcDirs.single())
            it.manifestFile.set(processManifestTask.manifestOutputFile)

            it.outputFile.set(intermediates.resolve("res.apk"))
        }

    val compilePluginJar = project.tasks.register("compilePluginJar") {
        it.group = TASK_GROUP
        it.dependsOn("createFullJarDebug") // Ensure JAR is built before copying

        val dependencies = project.configurations.getByName("implementation").dependencies
            .withType(ProjectDependency::class.java)
            .map { it.dependencyProject.path }
            .toList()

        it.logger.info("Resolved subproject dependencies for ${project.name}: $dependencies")

        dependencies.forEach { dependencyPath ->
            project.rootProject.findProject(dependencyPath)?.let { dependencyProject ->
                (dependencyProject.tasks.findByName("createFullJarDebug"))?.let { dependencyJarTask ->
                    it.dependsOn(dependencyJarTask)
                } ?: run {
                    project.logger.warn("Could not find createFullJarDebug task for dependency: $dependencyPath")
                }
            } ?: run {
                project.logger.warn("Could not find project for dependency: $dependencyPath")
            }
        }

        it.doFirst {
            if (extension.pluginClassName == null) {
                if (pluginClassFile.exists()) {
                    extension.pluginClassName = pluginClassFile.readText()
                }
            }
        }

        it.doLast {
            if (!extension.isCrossPlatform) {
                return@doLast
            }

            val jarTask = project.tasks.findByName("createFullJarDebug") ?: return@doLast
            val jarFile =
                jarTask.outputs.files.singleFile // Output directory of createFullJarDebug
            if (jarFile != null) {
                val targetDir = project.buildDir // Top-level build directory
                val targetFile = targetDir.resolve("${project.name}.jar")
                jarFile.copyTo(targetFile, overwrite = true)

                val jarFileList = mutableListOf<File>(jarFile)
                if (dependencies.isNotEmpty()) {
                    for (dependencyPath in dependencies) {
                        val dependency = project.rootProject.findProject(dependencyPath) ?: continue
                        val dependencyJarTask = dependency.tasks.findByName("createFullJarDebug") ?: continue
                        val dependencyJarFile = dependencyJarTask.outputs.files.singleFile
                        if (dependencyJarFile != null && dependencyJarFile.exists()) {
                            jarFileList.add(dependencyJarFile)
                        } else {
                            continue
                        }
                    }
                    mergeJars(jarFileList, targetFile)
                }

                extension.jarFileSize = jarFile.length()
                it.logger.lifecycle("Made Cloudstream cross-platform package at ${targetFile.absolutePath}")
            } else {
                it.logger.warn("Could not find JAR file!")
            }
        }
    }

    val ensureJarCompatibility = project.tasks.register("ensureJarCompatibility") {
        it.group = TASK_GROUP
        it.dependsOn("compilePluginJar")
        it.doLast { task ->
            if (!extension.isCrossPlatform) {
                return@doLast
            }

            val jarFile = File("${project.buildDir}/${project.name}.jar")
            if (!jarFile.exists()) {
                throw GradleException("Jar file does not exist.")
                return@doLast
            }

            // Run jdeps command
            try {
                val jdepsOutput = ByteArrayOutputStream()
                val jdepsCommand = listOf("jdeps", "--print-module-deps", jarFile.absolutePath)

                project.exec { execTask ->
                    execTask.setCommandLine(jdepsCommand)
                    execTask.setStandardOutput(jdepsOutput)
                    execTask.setErrorOutput(System.err)
                    execTask.setIgnoreExitValue(true)
                }

                val output = jdepsOutput.toString()

                // Check if 'android.' is in the output
                if (output.isEmpty()) {
                    task.logger.warn("No output from jdeps! Cannot analyze jar file for Android imports!")
                } else if (output.contains("android.")) {
                    throw GradleException("The cross-platform jar file contains Android imports! This will cause compatibility issues.\nRemove 'isCrossPlatform = true' or remove the Android imports.")
                } else {
                    task.logger.lifecycle("SUCCESS: The cross-platform jar file does not contain Android imports")
                }
            } catch (e: org.gradle.process.internal.ExecException) {
                task.logger.warn("Jdeps failed! Cannot analyze jar file for Android imports!")
            }
        }
    }

    project.afterEvaluate {
        val make = project.tasks.register("make", Zip::class.java) {
            val compileDexTask = compileDex.get()
            it.dependsOn(compileDexTask)
            if (extension.isCrossPlatform) {
                it.dependsOn(compilePluginJar)
            }

            val manifestFile = intermediates.resolve("manifest.json")
            it.from(manifestFile)
            it.doFirst {
                if (extension.pluginClassName == null) {
                    if (pluginClassFile.exists()) {
                        extension.pluginClassName = pluginClassFile.readText()
                    }
                }
                if (!extension.isLibrary) {
                    manifestFile.writeText(
                        JsonBuilder(
                            project.makeManifest(),
                            JsonGenerator.Options()
                                .excludeNulls()
                                .build()
                        ).toString()
                    )
                }
            }

            it.from(compileDexTask.outputFile)

            val zip = it as Zip

            if (extension.requiresResources) {
                zip.dependsOn(compileResources.get())
                it.from(project.provider {
                    val resApkFile = compileResources.get().outputFile.get().asFile
                    if (resApkFile.exists()) project.zipTree(resApkFile) else emptyList<File>()
                }) { copySpec ->
                    copySpec.exclude("AndroidManifest.xml")
                }
            }

            zip.isPreserveFileTimestamps = false
            zip.archiveBaseName.set(project.name)
            zip.archiveExtension.set("cs3")
            zip.archiveVersion.set("")
            zip.destinationDirectory.set(project.buildDir)

            it.doLast { task ->
                extension.fileSize = task.outputs.files.singleFile.length()
                task.logger.lifecycle(
                    "Made Cloudstream package at {} ({})",
                    task.outputs.files.singleFile,
                    task.outputs.files.singleFile.length().toReadableSize()
                )
            }
        }
        if (!extension.isLibrary) {
            project.rootProject.tasks.getByName("makePluginsJson").dependsOn(make)
        }
    }

    project.tasks.register("cleanCache", CleanCacheTask::class.java) {
        it.group = TASK_GROUP
    }

    project.tasks.register("deployWithAdb", DeployWithAdbTask::class.java) {
        it.group = TASK_GROUP
        if (!extension.isLibrary) {
            it.dependsOn("make")
        }
    }
}
