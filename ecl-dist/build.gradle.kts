import org.gradle.jvm.tasks.Jar

plugins {
    base
}

val bootProject = project(":ecl-boot")
val cliProject = project(":ecl-cli")
val bootJar = bootProject.tasks.named<Jar>("jar")
val cliJar = cliProject.tasks.named<Jar>("jar")
val installDist = bootProject.tasks.named("installDist")

fun normalizeForDelete(directory: File) {
    if (directory.exists()) {
        directory.walkBottomUp().forEach { it.setWritable(true, false) }
    }
}

tasks.register<Exec>("packageWindowsApp") {
    dependsOn(installDist)
    group = "distribution"
    description = "Build a Windows app image with ECL.exe."
    onlyIf { System.getProperty("os.name").lowercase().contains("win") }

    val outputDir = rootProject.file(providers.gradleProperty("windowsPackageDir").orElse("dist/windows").get())
    doFirst {
        val allowedRoot = rootProject.file("dist").canonicalFile.toPath()
        val resolvedOutput = outputDir.canonicalFile.toPath()
        if (resolvedOutput == allowedRoot || !resolvedOutput.startsWith(allowedRoot)) {
            throw GradleException("windowsPackageDir must be a child directory of $allowedRoot")
        }
        normalizeForDelete(outputDir)
        delete(outputDir)
        outputDir.mkdirs()
        val cliLauncher = layout.buildDirectory.file("jpackage/ecl-cli.properties").get().asFile
        cliLauncher.parentFile.mkdirs()
        cliLauncher.writeText(
            "main-class=com.ecl.cli.EclCli\n" +
                    "main-jar=${cliJar.get().archiveFileName.get()}\n" +
                    "win-console=true\n",
            Charsets.UTF_8
        )

        commandLine(
            File(System.getProperty("java.home"), "bin/jpackage.exe"),
            "--type", "app-image",
            "--name", "ECL",
            "--app-version", project.version.toString(),
            "--vendor", "ECL",
            "--dest", outputDir,
            "--input", bootProject.layout.buildDirectory.dir("install/ECL/lib").get().asFile,
            "--main-jar", bootJar.get().archiveFileName.get(),
            "--main-class", "com.ecl.ECL",
            "--add-launcher", "ECL-CLI=${cliLauncher.absolutePath}",
            "--icon", rootProject.file("ecl-gui/src/main/resources/icons/ecl-icon.ico"),
            "--java-options", "-Dfile.encoding=UTF-8"
        )
    }
}
