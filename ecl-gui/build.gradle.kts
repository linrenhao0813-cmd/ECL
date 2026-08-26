plugins {
    `java-library`
}

val javafxPlatform = providers.systemProperty("os.name")
    .zip(providers.systemProperty("os.arch")) { osName, architecture ->
        val os = osName.lowercase()
        val arm64 = architecture.equals("aarch64", ignoreCase = true)
                || architecture.equals("arm64", ignoreCase = true)
        when {
            os.contains("win") -> if (arm64) "win-aarch64" else "win"
            os.contains("mac") -> if (arm64) "mac-aarch64" else "mac"
            os.contains("linux") -> if (arm64) "linux-aarch64" else "linux"
            else -> throw GradleException("Unsupported JavaFX platform: $osName/$architecture")
        }
    }

dependencies {
    api(project(":ecl-core"))
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    implementation("org.openjfx:javafx-base:${libs.versions.javafx.get()}:${javafxPlatform.get()}")
    implementation("org.openjfx:javafx-graphics:${libs.versions.javafx.get()}:${javafxPlatform.get()}")
    implementation("org.openjfx:javafx-controls:${libs.versions.javafx.get()}:${javafxPlatform.get()}")
    implementation(libs.imageio.webp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testfx.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.testfx.monocle)
}

tasks.test {
    maxParallelForks = 1
    systemProperty("testfx.headless", "true")
    systemProperty("testfx.robot", "glass")
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("headless.geometry", "1920x1080-32")
    systemProperty("prism.order", "sw")
    systemProperty("java.awt.headless", "true")
    systemProperty("ecl.reduceMotion", "true")
    systemProperty("ecl.snapshot", "true")

    val isolatedAppData = layout.buildDirectory.dir("headless-test-appdata").get().asFile
    systemProperty("user.home", isolatedAppData.absolutePath)
    environment("APPDATA", isolatedAppData.absolutePath)
    doFirst { isolatedAppData.mkdirs() }
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
