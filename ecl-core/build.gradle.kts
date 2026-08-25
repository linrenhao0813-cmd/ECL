plugins {
    `java-library`
}

dependencies {
    implementation(libs.gson)
    implementation(libs.jackson.databind)
    implementation(libs.jna.platform)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
