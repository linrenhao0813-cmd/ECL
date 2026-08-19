import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    application
}

dependencies {
    implementation(project(":ecl-core"))
    implementation(project(":ecl-gui"))
    implementation(project(":ecl-cli"))
}

application {
    mainClass.set("com.ecl.ECL")
    applicationName = "ECL"
}

tasks.jar {
    manifest.attributes["Main-Class"] = "com.ecl.ECL"
}

tasks.named("distTar") {
    enabled = false
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.delete()
    }
}
