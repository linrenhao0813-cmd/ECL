import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    base
    alias(libs.plugins.spotbugs) apply false
}

allprojects {
    group = "com.ecl"
    version = "1.0.1"
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

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

        if (project.name == "ecl-core" || project.name == "ecl-gui") {
            val lineMinimum = if (project.name == "ecl-gui") 0.20 else 0.60
            val branchMinimum = if (project.name == "ecl-gui") 0.15 else 0.40
            tasks.withType<JacocoCoverageVerification>().configureEach {
                violationRules {
                    rule {
                        limit {
                            counter = "LINE"
                            value = "COVEREDRATIO"
                            minimum = lineMinimum.toBigDecimal()
                        }
                        limit {
                            counter = "BRANCH"
                            value = "COVEREDRATIO"
                            minimum = branchMinimum.toBigDecimal()
                        }
                    }
                }
            }
            tasks.named("check") {
                dependsOn(tasks.named("jacocoTestCoverageVerification"))
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
