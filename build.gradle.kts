import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
}

val fulcrumJavaVersion = JavaLanguageVersion.of(26)
val publishedSdkArtifacts = mapOf(
    ":platform:fulcrum-bom" to "fulcrum-sdk-bom",
    ":sdk:authoring-sdk" to "authoring-sdk",
    ":sdk:authority-sdk" to "authority-sdk",
    ":api:contract-api" to "contract-api",
    ":capability:capability-api" to "capability-api",
    ":host:host-api" to "host-api",
    ":host:tick-runtime-api" to "tick-runtime-api",
    ":api:kernel-api" to "kernel-api",
)
val publishedSdkArtifactIds = publishedSdkArtifacts.values.toSet()

fun MavenPublication.removeUnpublishedFulcrumDependencies(allowedArtifactIds: Set<String>) {
    pom.withXml {
        val dependencyNodes = (asNode().get("dependencies") as NodeList)
            .filterIsInstance<Node>()
            .flatMap { dependenciesNode -> dependenciesNode.children().filterIsInstance<Node>() }
        dependencyNodes
            .filter { dependencyNode ->
                dependencyNode.childText("groupId") == "sh.harold.fulcrum"
                        && dependencyNode.childText("artifactId") !in allowedArtifactIds
            }
            .forEach { dependencyNode -> dependencyNode.parent().remove(dependencyNode) }
    }
}

fun Node.childText(name: String): String? {
    return (get(name) as NodeList)
        .filterIsInstance<Node>()
        .firstOrNull()
        ?.text()
}

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
    ":data:store-memory",
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
    ":capability:capability-bundle-runtime",
    ":capability:capability-runtime",
)

val step6CheckedProjects = step5CheckedProjects + listOf(
)

val step7CheckedProjects = step6CheckedProjects + listOf(
    ":adapters:object-storage",
    ":core:artifact-layout",
    ":core:content-resolver",
)

val step8CheckedProjects = step7CheckedProjects + listOf(
    ":control:capability-backend-registration",
    ":distribution:service-launcher",
    ":host:effect-admission",
    ":sdk:authoring-sdk",
    ":sdk:authority-sdk",
    ":validation:auction-escrow-contract",
    ":validation:auction-escrow-backend",
    ":validation:auction-experience-bundle",
    ":validation:authoring-sdk-conformance",
    ":validation:authority-sdk-conformance",
    ":validation:escrow-e2e",
    ":validation:store-adapter-certification",
)

allprojects {
    group = "sh.harold.fulcrum"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    publishedSdkArtifacts[path]?.let { publishedArtifactId ->
        pluginManager.apply("maven-publish")
        tasks.withType<GenerateModuleMetadata>().configureEach {
            enabled = false
        }
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "fulcrumLocalPublication"
                    url = rootProject.layout.buildDirectory.dir("publication/sdk-maven").get().asFile.toURI()
                }
                maven {
                    name = "githubPackages"
                    url = uri("https://maven.pkg.github.com/haroldDOTsh/fulcrum")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orElse("")
                            .get()
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orElse("")
                            .get()
                    }
                }
            }
        }

        plugins.withId("java") {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])
                        artifactId = publishedArtifactId
                        removeUnpublishedFulcrumDependencies(publishedSdkArtifactIds)
                        pom {
                            name.set("Fulcrum ${publishedArtifactId}")
                            description.set("Fulcrum v2 published SDK surface: ${publishedArtifactId}")
                            url.set("https://github.com/haroldDOTsh/fulcrum")
                        }
                    }
                }
            }
        }

        plugins.withId("java-platform") {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("mavenJava") {
                        from(components["javaPlatform"])
                        artifactId = publishedArtifactId
                        pom {
                            name.set("Fulcrum SDK BOM")
                            description.set("Version alignment BOM for Fulcrum v2 author-facing SDK artifacts")
                            url.set("https://github.com/haroldDOTsh/fulcrum")
                        }
                    }
                }
            }
        }
    }

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
    description = "Runs the automated Step 6 capability substrate checks that exist so far."
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

tasks.register("clusterExistingE2e") {
    group = "verification"
    description = "Runs the existing-cluster E2E gate against the current or supplied k3d/kind Kubernetes context."
    dependsOn("step8Check")
    dependsOn(":distribution:service-launcher:lobbyClusterE2eVerify")
}

tasks.register("clusterK3sE2e") {
    group = "verification"
    description = "Compatibility task for the generated-cluster E2E gate; k3d is the default provider and kind is the parity provider."
    dependsOn(":distribution:service-launcher:clusterK3sDeleteExisting")
    dependsOn("step8Check")
    dependsOn(":distribution:service-launcher:clusterK3sE2e")
}

tasks.named("step8Check") {
    mustRunAfter(":distribution:service-launcher:clusterK3sDeleteExisting")
}

tasks.register("clusterE2e") {
    group = "verification"
    description = "Runs the canonical k3d-backed cluster E2E gate for the login-to-lobby production slice."
    dependsOn("clusterK3sE2e")
}

tasks.register("escrowE2e") {
    group = "verification"
    description = "Runs the auction escrow registration, experience, authority, and generated-cluster E2E gate."
    dependsOn("escrowLocalE2e")
    dependsOn("escrowClusterK3sE2e")
}

tasks.register("escrowLocalE2e") {
    group = "verification"
    description = "Runs the local auction escrow registration, experience, and authority E2E gate."
    dependsOn("step8Check")
    dependsOn(":validation:auction-escrow-backend:auctionEscrowImageContext")
    dependsOn(":validation:auction-escrow-backend:auctionEscrowRenderManifests")
    dependsOn(":validation:auction-experience-bundle:auctionExperiencePaperBundleContext")
    dependsOn(":validation:auction-experience-bundle:auctionExperiencePaperGameserverImageContext")
    dependsOn(":validation:escrow-e2e:escrowWitnessImageContext")
    dependsOn(":validation:escrow-e2e:escrowWitnessRenderManifests")
    dependsOn(":validation:architecture:test")
    dependsOn(":validation:escrow-e2e:test")
}

tasks.register("escrowClusterK3sE2e") {
    group = "verification"
    description = "Runs the generated k3d/kind escrow cluster E2E gate with the validation Paper bundle overlay."
    dependsOn("step8Check")
    dependsOn(":distribution:service-launcher:clusterK3sImportImages")
    dependsOn(":distribution:service-launcher:lobbyClusterE2eVerify")
    dependsOn(":validation:auction-escrow-backend:auctionEscrowClusterRestartProof")
    dependsOn(":validation:escrow-e2e:escrowWitnessClusterRun")
    finalizedBy(":distribution:service-launcher:clusterK3sStop")
}

tasks.register("escrowClusterE2e") {
    group = "verification"
    description = "Runs the canonical generated-cluster escrow E2E gate."
    dependsOn("escrowClusterK3sE2e")
}

tasks.register("escrowClusterExistingE2e") {
    group = "verification"
    description = "Runs the existing-cluster lobby gate, then restart-proves escrow and runs the validation witness Job."
    dependsOn(":validation:auction-experience-bundle:auctionExperiencePaperGameserverImageReceipt")
    dependsOn("clusterExistingE2e")
    dependsOn(":validation:auction-escrow-backend:auctionEscrowClusterRestartProof")
    dependsOn(":validation:escrow-e2e:escrowWitnessClusterRun")
}

gradle.projectsEvaluated {
    val auctionExperiencePaperGameserverImage = project(":validation:auction-experience-bundle")
        .tasks
        .named("auctionExperiencePaperGameserverImage")
    val auctionExperiencePaperGameserverImageReceipt = project(":validation:auction-experience-bundle")
        .tasks
        .named("auctionExperiencePaperGameserverImageReceipt")
    val escrowClusterRequested = gradle.startParameter.taskNames.any {
        it == "escrowE2e"
                || it.endsWith(":escrowE2e")
                || it == "escrowClusterE2e"
                || it.endsWith(":escrowClusterE2e")
                || it == "escrowClusterK3sE2e"
                || it.endsWith(":escrowClusterK3sE2e")
                || it == "escrowClusterExistingE2e"
                || it.endsWith(":escrowClusterExistingE2e")
    }
    if (escrowClusterRequested) {
        val serviceLauncherTasks = project(":distribution:service-launcher").tasks
        val paperGameserverImage = serviceLauncherTasks.named("paperGameserverImage")
        val serviceLauncherImage = serviceLauncherTasks.named("serviceLauncherImage")
        val velocityProxyImage = serviceLauncherTasks.named("velocityProxyImage")
        val clusterK3sImportImages = serviceLauncherTasks.named("clusterK3sImportImages")
        val lobbyClusterE2eVerify = serviceLauncherTasks.named("lobbyClusterE2eVerify")
        val auctionEscrowTasks = project(":validation:auction-escrow-backend").tasks
        val auctionEscrowImage = auctionEscrowTasks.named("auctionEscrowImage")
        val auctionEscrowClusterImportImage = auctionEscrowTasks.named("auctionEscrowClusterImportImage")
        val auctionEscrowClusterApply = auctionEscrowTasks.named("auctionEscrowClusterApply")
        val auctionEscrowClusterRestartProof = auctionEscrowTasks.named("auctionEscrowClusterRestartProof")
        val escrowWitnessTasks = project(":validation:escrow-e2e").tasks
        val escrowWitnessImage = escrowWitnessTasks.named("escrowWitnessImage")
        val escrowWitnessClusterImportImage = escrowWitnessTasks.named("escrowWitnessClusterImportImage")
        val escrowWitnessClusterApply = escrowWitnessTasks.named("escrowWitnessClusterApply")
        auctionExperiencePaperGameserverImage.configure {
            mustRunAfter(paperGameserverImage)
        }
        serviceLauncherImage.configure {
            mustRunAfter(auctionExperiencePaperGameserverImageReceipt)
        }
        velocityProxyImage.configure {
            mustRunAfter(serviceLauncherImage)
        }
        auctionEscrowImage.configure {
            mustRunAfter(clusterK3sImportImages)
        }
        escrowWitnessImage.configure {
            mustRunAfter(auctionEscrowImage)
        }
        clusterK3sImportImages.configure {
            dependsOn(auctionExperiencePaperGameserverImageReceipt)
        }
        serviceLauncherTasks.named("paperAgonesApply").configure {
            dependsOn(auctionExperiencePaperGameserverImageReceipt)
        }
        auctionEscrowClusterImportImage.configure {
            dependsOn(serviceLauncherTasks.named("clusterK3sStart"))
            mustRunAfter(clusterK3sImportImages)
        }
        auctionEscrowClusterApply.configure {
            dependsOn(lobbyClusterE2eVerify)
            mustRunAfter(lobbyClusterE2eVerify)
        }
        escrowWitnessClusterImportImage.configure {
            dependsOn(serviceLauncherTasks.named("clusterK3sStart"))
            mustRunAfter(auctionEscrowClusterImportImage)
            mustRunAfter(clusterK3sImportImages)
        }
        escrowWitnessClusterApply.configure {
            dependsOn(auctionEscrowClusterRestartProof)
            mustRunAfter(auctionEscrowClusterRestartProof)
        }
    }
    project(":validation:auction-escrow-backend")
        .tasks
        .named("auctionEscrowClusterDeploy")
        .configure {
            mustRunAfter(project(":distribution:service-launcher").tasks.named("lobbyClusterE2eVerify"))
        }
    project(":validation:auction-escrow-backend")
        .tasks
        .named("auctionEscrowClusterRestartProof")
        .configure {
            mustRunAfter(tasks.named("clusterExistingE2e"))
            mustRunAfter(project(":distribution:service-launcher").tasks.named("lobbyClusterE2eVerify"))
        }
    project(":validation:escrow-e2e")
        .tasks
        .named("escrowWitnessClusterRun")
        .configure {
            mustRunAfter(project(":validation:auction-escrow-backend").tasks.named("auctionEscrowClusterRestartProof"))
        }
}

tasks.named("check") {
    dependsOn("step8Check")
}

tasks.register("publishSdkToLocalPublicationRepo") {
    group = "publishing"
    description = "Publishes only the ADR-0031 Fulcrum SDK/BOM coordinates to build/publication/sdk-maven."
    dependsOn(publishedSdkArtifacts.keys.map {
        "$it:publishAllPublicationsToFulcrumLocalPublicationRepository"
    })
}

tasks.register("publishSdkToGitHubPackages") {
    group = "publishing"
    description = "Publishes only the ADR-0031 Fulcrum SDK/BOM coordinates to GitHub Packages."
    dependsOn(publishedSdkArtifacts.keys.map {
        "$it:publishAllPublicationsToGithubPackagesRepository"
    })
}

tasks.register("publishFulcrumDistribution") {
    group = "publishing"
    description = "Publishes the Fulcrum v2 GitHub Packages SDK/BOM and signed GHCR OCI images."
    dependsOn("publishSdkToGitHubPackages")
    dependsOn(":distribution:service-launcher:signFulcrumImages")
    dependsOn(":distribution:service-launcher:writeFulcrumReleaseManifest")
}

tasks.register("releaseRehearsal") {
    group = "verification"
    description = "Runs the current no-source release-shaped rehearsal gate with operator, install, and author-loop surfaces."
    dependsOn(":distribution:service-launcher:operatorDistributionZip")
    dependsOn(":distribution:service-launcher:operatorDeploymentSurfaceTest")
    dependsOn(":distribution:service-launcher:bundleInstallSurfaceTest")
    dependsOn(":distribution:service-launcher:authorLoopSurfaceTest")
    dependsOn(":validation:authoring-sdk-conformance:test")
    dependsOn(":validation:architecture:test")
}
