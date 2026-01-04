import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    application
}

// Configuration - use JavaFX 25 to match the project config
val javaFXVersion = "25"

// Capture paths as strings at configuration time - these are serializable
val buildDirPath: String = layout.buildDirectory.get().asFile.absolutePath
val jmodsDirPath: String = "${buildDirPath}/jmods"
val rootDirPath: String = rootProject.layout.projectDirectory.asFile.absolutePath
val distDirPath: String = "${rootDirPath}/dist"
val javafxVersionStr: String = javaFXVersion

// Get the Java toolchain launcher to find the correct JAVA_HOME for Java 25
val javaExtension = extensions.getByType<JavaPluginExtension>()
val toolchainService = extensions.getByType<JavaToolchainService>()
val toolchainJavaHome: String = toolchainService.launcherFor(javaExtension.toolchain).get().metadata.installationPath.asFile.absolutePath

// Clean tasks
tasks.register<Delete>("cleanJMods") {
    description = "Cleans the downloaded JavaFX jmods"
    delete(File(jmodsDirPath))
}

// Abstract class for downloading JMods - configuration cache compatible
abstract class DownloadJModsTask : DefaultTask() {
    @get:Input
    abstract val jmodsDir: Property<String>

    @get:Input
    abstract val jfxVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val jmodsDirPath = jmodsDir.get()
        val version = jfxVersion.get()
        val jmodsDirFile = File(jmodsDirPath)
        if (!jmodsDirFile.exists()) {
            val filename = "openjfx-${version}_windows-x64_bin-jmods.zip"
            val zipFile = File("${jmodsDirPath}/${filename}")
            jmodsDirFile.mkdirs()
            println("Downloading JavaFX jmods...")
            URI("https://download2.gluonhq.com/openjfx/${version}/${filename}")
                .toURL()
                .openStream().use { input ->
                    zipFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            // Unzip using Java's ZipInputStream
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val newFile = File(jmodsDirPath, entry.name)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        newFile.outputStream().use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            zipFile.delete()
        }
    }
}

tasks.register<DownloadJModsTask>("downloadJMods") {
    description = "Downloads JavaFX jmods for jlink/jpackage"
    jmodsDir.set(jmodsDirPath)
    jfxVersion.set(javafxVersionStr)
    outputDir.set(File(jmodsDirPath))
}

// Abstract class for cleaning directories with read-only file handling
abstract class CleanWithAttribTask : DefaultTask() {
    @get:Input
    abstract val targetDir: Property<String>

    @get:Internal
    abstract val dirsToDelete: ListProperty<String>

    @TaskAction
    fun cleanDirs() {
        // Remove read-only attributes from all directories to be deleted
        dirsToDelete.get().forEach { path ->
            val dir = File(path)
            if (dir.exists()) {
                // Remove read-only attributes on Windows using attrib command
                ProcessBuilder("cmd", "/c", "attrib", "-R", "${dir.absolutePath}\\*.*", "/S", "/D")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
        // Now delete the directories
        dirsToDelete.get().forEach { path ->
            File(path).deleteRecursively()
        }
    }
}

tasks.register<CleanWithAttribTask>("cleanJlink") {
    description = "Cleans the app image created by jlink task"
    val imageDirPath = "${rootDirPath}/dist/image"
    targetDir.set(imageDirPath)
    dirsToDelete.set(listOf(imageDirPath))
}

tasks.named("clean") {
    dependsOn("cleanJlink")
}

// Capture runtime classpath file paths at configuration time
val runtimeClasspathFiles: List<String> = configurations.named("runtimeClasspath").get()
    .resolvedConfiguration.resolvedArtifacts.map { it.file.parentFile.absolutePath }.distinct()

// Capture jar task output directory at configuration time
val jarTask = tasks.named<Jar>("jar")
val jarDirPath: String = jarTask.get().destinationDirectory.get().asFile.absolutePath

tasks.register<Exec>("jlink") {
    group = "Distribution"
    description = "Build a custom JRE image with jlink"
    dependsOn(jarTask)
    dependsOn("cleanJlink")
    dependsOn("downloadJMods")

    // Capture values at configuration time - all are Strings
    val runtimeJarDirs = runtimeClasspathFiles
    val javaHome = toolchainJavaHome
    val outputDir = "${rootDirPath}/dist/image"
    val jmodsFxPath = "${jmodsDirPath}/javafx-jmods-${javafxVersionStr}"

    val modulePath = listOf(
        "$javaHome/jmods",
        jarDirPath,
        jmodsFxPath
    ) + runtimeJarDirs

    commandLine(
        "$javaHome/bin/jlink",
        "--module-path", modulePath.joinToString(";"),
        "--add-modules", "app",
        "--output", outputDir,
        "--bind-services",
        "--strip-debug",
        "--no-man-pages",
        "--no-header-files"
    )
}

tasks.register<CleanWithAttribTask>("cleanJpackage") {
    description = "Cleans the app image created by jpackage task"
    val tmpDirPath = "${buildDirPath}/tmp/jpackage"
    targetDir.set(tmpDirPath)
    dirsToDelete.set(listOf(tmpDirPath, distDirPath))
}

tasks.named("clean") {
    dependsOn("cleanJpackage")
}

// Abstract class for jpackage task
abstract class JPackageTask : DefaultTask() {
    @get:Input
    abstract val tmpDir: Property<String>

    @get:Input
    abstract val destDir: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val inputDir: Property<String>

    @TaskAction
    fun runJpackage() {
        val tmp = tmpDir.get()
        val dest = destDir.get()

        println("Generating application installable...")
        File(tmp).mkdirs()
        File(dest).mkdirs()

        val command = listOf(
            javaHome.get() + "/bin/jpackage",
            "--name", "Template",
            "--temp", tmp,
            "--module", "app/app.AppMain",
            "--module-path", modulePath.get(),
            "--input", inputDir.get(),
            "--jlink-options", "--bind-services --strip-native-commands --strip-debug --no-man-pages --no-header-files",
            "--java-options", "-Xmx1g --enable-native-access=javafx.graphics",
            "--dest", dest,
            "--description", "Template Project",
            "--vendor", "TCF",
            "--copyright", "Copyright 2026",
            // Windows-specific options
            "--app-version", "1.0",
            "--win-per-user-install",
            "--win-menu-group", "JavaProjects",
            "--win-shortcut",
            "--win-shortcut-prompt"
        )

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        // Read and print all output in real-time
        process.inputStream.bufferedReader().use { reader ->
            reader.lines().forEach { line ->
                println(line)
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("jpackage failed with exit code $exitCode")
        }
    }
}

tasks.register<JPackageTask>("jpackage") {
    group = "Distribution"
    description = "Build an installation package"
    dependsOn(jarTask)
    dependsOn("cleanJpackage")
    dependsOn("downloadJMods")

    // Capture values at configuration time - all are Strings
    val runtimeJarDirs = runtimeClasspathFiles
    val javaHomePath = toolchainJavaHome
    val jmodsFxPath = "${jmodsDirPath}/javafx-jmods-${javafxVersionStr}"

    val modulePathValue = listOf(
        "$javaHomePath/jmods",
        jarDirPath,
        jmodsFxPath
    ) + runtimeJarDirs

    tmpDir.set("${buildDirPath}/tmp/jpackage")
    destDir.set(distDirPath)
    javaHome.set(javaHomePath)
    modulePath.set(modulePathValue.joinToString(";"))
    inputDir.set(jarDirPath)
}
