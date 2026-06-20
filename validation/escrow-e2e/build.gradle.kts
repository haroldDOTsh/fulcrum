import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

plugins {
    `java-library`
    application
}

application {
    applicationName = "escrow-e2e-witness"
    mainClass.set("sh.harold.fulcrum.validation.escrowe2e.HeadlessAuctionBotWitnessMain")
}

dependencies {
    implementation(project(":control:capability-backend-registration"))
    implementation(project(":sdk:authority-sdk"))
    implementation(project(":validation:auction-escrow-backend"))
    implementation(project(":validation:auction-escrow-contract"))
    implementation(project(":validation:auction-experience-bundle"))
}

val defaultEscrowWitnessImage = "ghcr.io/sh-harold/fulcrum-escrow-e2e-witness:dev"
val escrowWitnessImageTag = providers.gradleProperty("fulcrum.escrowWitnessImage")
    .orElse(defaultEscrowWitnessImage)

val escrowWitnessImageContext by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the Docker build context for the escrow E2E witness image."
    dependsOn(tasks.named("installDist"))

    into(layout.buildDirectory.dir("escrow-witness-image"))
    from("src/main/resources/fulcrum/container/escrow-witness")
    from(layout.buildDirectory.dir("install/escrow-e2e-witness")) {
        into("escrow-e2e-witness")
    }
}

tasks.register<Exec>("escrowWitnessImage") {
    group = "distribution"
    description = "Builds the escrow E2E witness container image from the assembled context."
    dependsOn(escrowWitnessImageContext)
    workingDir(layout.buildDirectory.dir("escrow-witness-image"))
    doFirst {
        commandLine("docker", "build", "-t", escrowWitnessImageTag.get(), ".")
    }
}

val escrowWitnessManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/escrow-e2e/escrow-witness.yaml")
val renderedEscrowWitnessManifest =
    layout.buildDirectory.file("kubernetes/escrow-e2e/escrow-witness.yaml")

tasks.register<Sync>("escrowWitnessRenderManifests") {
    group = "distribution"
    description = "Renders escrow E2E witness Kubernetes manifests with the effective container image tag."
    inputs.property("escrowWitnessImageTag", escrowWitnessImageTag)
    into(layout.buildDirectory.dir("kubernetes/escrow-e2e"))
    from(escrowWitnessManifest) {
        filter { line: String ->
            line.replace(defaultEscrowWitnessImage, escrowWitnessImageTag.get())
        }
    }
}

val kubeconfig = providers.gradleProperty("fulcrum.kubeconfig")
val kubeContext = providers.gradleProperty("fulcrum.kubeContext")
val generatedClusterKubeconfig = rootProject.layout.projectDirectory.file(
    "distribution/service-launcher/build/cluster-e2e/kubeconfig.yaml")
val clusterProvider = providers.gradleProperty("fulcrum.clusterProvider")
    .orElse("k3d")
val clusterName = providers.gradleProperty("fulcrum.clusterName")
    .orElse(providers.gradleProperty("fulcrum.k3dClusterName"))
    .orElse(providers.gradleProperty("fulcrum.kindClusterName"))
    .orElse("fulcrum-cluster-e2e")
val escrowWitnessNamespace = providers.gradleProperty("fulcrum.escrowWitnessNamespace")
    .orElse(providers.gradleProperty("fulcrum.lobbyNamespace"))
    .orElse("fulcrum-lobby")
val escrowWitnessWaitTimeout = providers.gradleProperty("fulcrum.escrowWitnessWaitTimeout")
    .orElse("180s")

fun effectiveKubeconfigPath(): String? {
    return kubeconfig.orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { rootProject.file(it).absolutePath }
        ?: generatedClusterKubeconfig.asFile.takeIf { it.exists() }?.absolutePath
}

fun kubectlCommand(vararg args: String): List<String> = buildList {
    add("kubectl")
    effectiveKubeconfigPath()?.let {
        add("--kubeconfig")
        add(it)
    }
    kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {
        add("--context")
        add(it)
    }
    addAll(args)
}

fun generatedClusterProvider(): String = clusterProvider.get().lowercase()

tasks.register<Exec>("escrowWitnessClusterImportImage") {
    group = "deployment"
    description = "Imports the locally built escrow E2E witness image into the configured generated k3d/kind cluster."
    dependsOn(tasks.named("escrowWitnessImage"))
    doFirst {
        when (val provider = generatedClusterProvider()) {
            "k3d" -> commandLine("k3d", "image", "import", escrowWitnessImageTag.get(), "--cluster", clusterName.get())
            "kind" -> commandLine("kind", "load", "docker-image", "--name", clusterName.get(), escrowWitnessImageTag.get())
            else -> throw GradleException("Unsupported cluster provider `$provider`; expected k3d or kind.")
        }
    }
}

tasks.register("escrowWitnessClusterApply") {
    group = "deployment"
    description = "Applies the escrow E2E witness Job manifest to a prepared Fulcrum cluster."
    dependsOn(tasks.named("escrowWitnessRenderManifests"))
    doLast {
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                escrowWitnessNamespace.get(),
                "delete",
                "job",
                "fulcrum-escrow-e2e-witness",
                "--ignore-not-found=true"))
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(kubectlCommand("apply", "-f", renderedEscrowWitnessManifest.get().asFile.absolutePath))
        }.result.get().assertNormalExitValue()
    }
}

tasks.register("escrowWitnessClusterRun") {
    group = "verification"
    description = "Runs the validation-owned escrow E2E witness Job and prints its settlement certificate."
    dependsOn(
        tasks.named("escrowWitnessClusterImportImage"),
        tasks.named("escrowWitnessClusterApply"))
    doLast {
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                escrowWitnessNamespace.get(),
                "wait",
                "--for=condition=complete",
                "job/fulcrum-escrow-e2e-witness",
                "--timeout=${escrowWitnessWaitTimeout.get()}"))
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                escrowWitnessNamespace.get(),
                "logs",
                "job/fulcrum-escrow-e2e-witness",
                "--tail=-1"))
        }.result.get().assertNormalExitValue()
    }
}

tasks.named("escrowWitnessClusterApply") {
    mustRunAfter(tasks.named("escrowWitnessClusterImportImage"))
}

tasks.named("check") {
    dependsOn(escrowWitnessImageContext)
    dependsOn(tasks.named("escrowWitnessRenderManifests"))
}
