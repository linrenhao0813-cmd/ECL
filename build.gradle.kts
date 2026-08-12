import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    base
    alias(libs.plugins.spotbugs) apply false
}

allprojects {
    group = "com.ecl"
    version = "1.0.0"
}

subprojects {
    pluginManager.withPlugin("java") {
        apply(plugin = "checkstyle")
        apply(plugin = "jacoco")
        apply(plugin = "com.github.spotbugs")

        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }

        extensions.configure<CheckstyleExtension> {
            toolVersion = libs.versions.checkstyle.get()
            configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            finalizedBy(tasks.named("jacocoTestReport"))
        }

        tasks.withType<JacocoReport>().configureEach {
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        tasks.withType<SpotBugsTask>().configureEach {
            reportLevel.set(Confidence.HIGH)
            excludeFilter.set(rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml"))
            reports.maybeCreate("html").required.set(true)
        }
    }
}

tasks.register("captureLauncherUi") {
    group = "verification"
    dependsOn(":ecl-gui:captureLauncherUi")
}

tasks.register("run") {
    group = "application"
    dependsOn(":ecl-boot:run")
}

tasks.register("installDist") {
    group = "distribution"
    dependsOn(":ecl-boot:installDist")
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

tasks.named("check") {
    dependsOn(subprojects.mapNotNull { project ->
        if (project.name == "ecl-dist") null else "${project.path}:check"
    })
}

tasks.register("packageWindowsApp") {
    group = "distribution"
    dependsOn(":ecl-dist:packageWindowsApp")
}

tasks.register("packageMacApp") {
    group = "distribution"
    dependsOn(":ecl-dist:packageMacApp")
}

tasks.register("packageLinuxApp") {
    group = "distribution"
    dependsOn(":ecl-dist:packageLinuxApp")
}
