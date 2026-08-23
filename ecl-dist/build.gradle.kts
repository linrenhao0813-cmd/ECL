import org.gradle.jvm.tasks.Jar

plugins {
    base
}

val bootProject = project(":ecl-boot")
val bootJar = bootProject.tasks.named<Jar>("jar")
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
            "--icon", rootProject.file("ecl-gui/src/main/resources/icons/ecl-icon.ico"),
            "--java-options", "-Dfile.encoding=UTF-8"
        )
    }
}
