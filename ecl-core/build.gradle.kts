plugins {
    `java-library`
}

dependencies {
    implementation(libs.gson)
    implementation(libs.jackson.databind)
    implementation(libs.jna.platform)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("launcherVersion", project.version.toString())
    filesMatching("ecl-build.properties") {
        expand("launcherVersion" to project.version.toString())
    }
}

tasks.test {
    systemProperty("ecl.expectedVersion", project.version.toString())
    // Keep tests away from the real %USERPROFILE%\.ecl and from the user's log directory.
    // ECLConfig and Logback share the APPDATA root (falling back to user.home).
    val isolatedAppData = layout.buildDirectory.dir("headless-test-appdata").get().asFile
    systemProperty("user.home", isolatedAppData.absolutePath)
    environment("APPDATA", isolatedAppData.absolutePath)
    doFirst { isolatedAppData.mkdirs() }
}
