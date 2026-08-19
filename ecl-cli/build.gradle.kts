import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":ecl-core"))
    implementation(libs.jackson.databind)
    api(libs.picocli)
    annotationProcessor(libs.picocli)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("com.ecl.cli.EclCli")
    applicationName = "ecl-cli"
}

tasks.named("distTar") {
    enabled = false
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.delete()
    }
}
