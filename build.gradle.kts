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
    ":data:authority-runtime",
    ":data:artifact-authority",
    ":data:presence-authority",
    ":data:route-contract",
    ":data:route-authority",
    ":data:session-authority",
    ":data:store-cassandra",
    ":data:store-kafka",
    ":data:store-postgresql",
    ":data:store-valkey",
    ":data:subject-authority",
)

val step2CheckedProjects = step1CheckedProjects + listOf(
    ":adapters:agones-allocator",
    ":adapters:agones-fake",
    ":host:paper-agent",
    ":host:velocity-agent",
    ":host:worker-agent",
)

val step3CheckedProjects = step2CheckedProjects + listOf(
    ":core:session-runtime",
    ":host:tick-runtime-api",
)

val step4CheckedProjects = step3CheckedProjects + listOf(
    ":control:allocation-bridge",
    ":control:capability-enablement-controller",
    ":control:fault-controller",
    ":control:instance-registry-controller",
    ":control:lifecycle-controller",
    ":control:queue-controller",
    ":control:route-controller",
)

val step5CheckedProjects = step4CheckedProjects + listOf(
    ":capability:capability-api",
    ":capability:capability-runtime",
)

val step6CheckedProjects = step5CheckedProjects + listOf(
    ":standard-capabilities:standard-contracts",
    ":standard-capabilities:player-profile",
    ":standard-capabilities:rank",
    ":standard-capabilities:chat-decoration",
    ":standard-capabilities:party",
    ":standard-capabilities:friends",
    ":standard-capabilities:guild",
    ":standard-capabilities:economy",
    ":standard-capabilities:stats",
    ":standard-capabilities:auction",
    ":standard-capabilities:punishment",
    ":validation:standard-capabilities",
)

val step7CheckedProjects = step6CheckedProjects + listOf(
    ":adapters:object-storage",
    ":core:artifact-layout",
    ":core:content-resolver",
    ":standard-capabilities:realm",
)

val step8CheckedProjects = step7CheckedProjects + listOf(
    ":distribution:service-launcher",
    ":host:effect-admission",
    ":validation:fleet-e2e",
    ":validation:store-adapter-certification",
    ":validation:synthetic-load",
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

tasks.register("step2Check") {
    group = "verification"
    description = "Runs the automated Step 2 host runtime checks that exist so far."
    dependsOn(step2CheckedProjects.map { "$it:check" })
}

tasks.register("step3Check") {
    group = "verification"
    description = "Runs the automated Step 3 Session runtime checks that exist so far."
    dependsOn(step3CheckedProjects.map { "$it:check" })
}

tasks.register("step4Check") {
    group = "verification"
    description = "Runs the automated Step 4 control-plane checks that exist so far."
    dependsOn(step4CheckedProjects.map { "$it:check" })
}

tasks.register("step5Check") {
    group = "verification"
    description = "Runs the automated Step 5 capability substrate checks that exist so far."
    dependsOn(step5CheckedProjects.map { "$it:check" })
}

tasks.register("step6Check") {
    group = "verification"
    description = "Runs the automated Step 6 standard capability checks that exist so far."
    dependsOn(step6CheckedProjects.map { "$it:check" })
}

tasks.register("step7Check") {
    group = "verification"
    description = "Runs the automated Step 7 artifact and content checks that exist so far."
    dependsOn(step7CheckedProjects.map { "$it:check" })
}

tasks.register("step8Check") {
    group = "verification"
    description = "Runs the automated Step 8 safety and hardening checks that exist so far."
    dependsOn(step8CheckedProjects.map { "$it:check" })
}

tasks.register("clusterE2e") {
    group = "verification"
    description = "Runs the current production-shaped cluster E2E gate: deploys Paper Agones plus Velocity L4, then verifies the public Minecraft endpoint."
    dependsOn(":distribution:service-launcher:lobbyClusterE2eVerify")
}

tasks.named("check") {
    dependsOn("step8Check")
}
