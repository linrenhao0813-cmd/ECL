import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    base
    alias(libs.plugins.spotbugs) apply false
}

val launcherVersion = providers.gradleProperty("version").get()

allprojects {
    group = "com.ecl"
    version = launcherVersion
}

tasks.register("validateReleaseVersion") {
    group = "verification"
    val releaseTag = providers.gradleProperty("releaseTag")
    doLast {
        val tag = releaseTag.orNull ?: throw GradleException("releaseTag is required")
        if (tag.replaceFirst(Regex("^[vV]"), "") != launcherVersion) {
            throw GradleException("Release tag $tag does not match launcher version $launcherVersion")
        }
    }
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
            // Keep the gate at HIGH confidence so it stays actionable, but raise the analysis
            // effort to MAX: SpotBugs then runs its full detector set and interprocedural checks
            // instead of the default subset. Lowering the level to MEDIUM was evaluated and
            // rejected: it surfaces 156 mostly-noisy findings (96 NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE
            // from Path#getParent-style calls plus EI_EXPOSE_REP records) that would need blanket
            // suppressions and stop catching anything new.
            reportLevel.set(Confidence.HIGH)
            effort.set(Effort.MAX)
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
