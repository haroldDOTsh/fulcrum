import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
}

val fulcrumJavaVersion = JavaLanguageVersion.of(26)
val step0CheckedProjects = listOf(
    ":platform:fulcrum-bom",
    ":api:kernel-api",
    ":api:contract-api",
    ":core:manifest-core",
    ":data:contract-declarations",
    ":data:contract-codegen",
    ":capability:capability-api",
    ":host:host-api",
    ":distribution:profiles",
    ":testkit:architecture-testkit",
    ":testkit:substrate-testkit",
    ":validation:architecture",
)

val step1CheckedProjects = step0CheckedProjects + listOf(
    ":data:authority-core",
    ":data:artifact-authority",
    ":data:presence-authority",
    ":data:route-authority",
    ":data:session-authority",
)

allprojects {
    group = "sh.harold.fulcrum"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(fulcrumJavaVersion)
            }
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(26)
        }

        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.add("testImplementation", project.dependencies.platform(libs.findLibrary("junit-bom").orElseThrow()))
        dependencies.add("testImplementation", libs.findLibrary("junit-jupiter").orElseThrow())
        dependencies.add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").orElseThrow())

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register("step0Check") {
    group = "verification"
    description = "Runs the automated Step 0 foundation checks that exist so far."
    dependsOn(step0CheckedProjects.map { "$it:check" })
}

tasks.register("step1Check") {
    group = "verification"
    description = "Runs the automated Step 1 authority checks that exist so far."
    dependsOn(step1CheckedProjects.map { "$it:check" })
}

tasks.named("check") {
    dependsOn("step1Check")
}
