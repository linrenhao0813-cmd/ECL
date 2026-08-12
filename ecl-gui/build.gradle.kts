plugins {
    `java-library`
}

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val detectedJavafxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") && (osArch.contains("aarch64") || osArch.contains("arm64")) -> "mac-aarch64"
    osName.contains("mac") -> "mac"
    else -> "linux"
}
val javafxPlatform = providers.gradleProperty("javafxPlatform").orElse(detectedJavafxPlatform)

dependencies {
    api(project(":ecl-core"))
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:${javafxPlatform.get()}")
    implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:${javafxPlatform.get()}")
    implementation("org.openjfx:javafx-controls:${libs.versions.javafx.get()}:${javafxPlatform.get()}")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.register<JavaExec>("captureLauncherUi") {
    dependsOn(tasks.testClasses)
    group = "verification"
    description = "Render the JavaFX launcher window to a PNG for visual QA."

    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.ecl.ui.LauncherUiSnapshot")
    systemProperty("ecl.reduceMotion", "true")
    systemProperty("ecl.snapshot", "true")
    systemProperty(
        "ecl.snapshot.path",
        providers.gradleProperty("uiSnapshotPath")
            .orElse(layout.buildDirectory.file("visual-qa/ecl-home.png").map { it.asFile.absolutePath })
            .get()
    )
    systemProperty("ecl.snapshot.mode", providers.gradleProperty("uiSnapshotMode").orElse("home").get())

    val isolatedAppData = layout.buildDirectory.dir("ui-preview-appdata").get().asFile
    systemProperty("user.home", isolatedAppData.absolutePath)
    environment("APPDATA", isolatedAppData.absolutePath)
    doFirst { isolatedAppData.mkdirs() }
}
