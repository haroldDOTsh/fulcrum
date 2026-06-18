import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import java.io.IOException
import java.util.concurrent.TimeUnit

plugins {
    application
}

application {
    applicationName = "fulcrum"
    mainClass.set("sh.harold.fulcrum.distribution.launcher.FulcrumLauncher")
}

dependencies {
    implementation(project(":api:contract-api"))
    implementation(project(":api:kernel-api"))
    implementation(project(":adapters:agones-allocator"))
    implementation(project(":adapters:object-storage"))
    implementation(project(":data:artifact-authority"))
    implementation(project(":data:authority-runtime"))
    implementation(project(":data:presence-authority"))
    implementation(project(":data:route-authority"))
    implementation(project(":data:session-authority"))
    implementation(project(":data:store-cassandra"))
    implementation(project(":data:store-kafka"))
    implementation(project(":data:store-postgresql"))
    implementation(project(":data:store-valkey"))
    implementation(project(":data:subject-authority"))
    implementation(project(":control:allocation-bridge"))
    implementation(project(":control:capability-enablement-controller"))
    implementation(project(":control:fault-controller"))
    implementation(project(":control:instance-registry-controller"))
    implementation(project(":control:lifecycle-controller"))
    implementation(project(":control:queue-controller"))
    implementation(project(":control:route-controller"))
    implementation(project(":host:host-api"))
    implementation(project(":host:paper-agent"))
    implementation(project(":host:velocity-agent"))
    implementation(project(":host:worker-agent"))
    implementation(project(":standard-capabilities:chat-decoration"))
    implementation(project(":standard-capabilities:economy"))
    implementation(project(":standard-capabilities:player-profile"))
    implementation(project(":standard-capabilities:punishment"))
    implementation(project(":standard-capabilities:rank"))
    implementation(project(":standard-capabilities:stats"))

    runtimeOnly(project(":adapters:agones-fake"))
    runtimeOnly(project(":capability:capability-runtime"))
    runtimeOnly(project(":distribution:profiles"))
    runtimeOnly(project(":host:effect-admission"))
    runtimeOnly(project(":host:tick-runtime-api"))
    runtimeOnly(project(":standard-capabilities:party"))
    runtimeOnly(project(":standard-capabilities:friends"))
    runtimeOnly(project(":standard-capabilities:guild"))
    runtimeOnly(project(":standard-capabilities:economy"))
    runtimeOnly(project(":standard-capabilities:stats"))
    runtimeOnly(project(":standard-capabilities:auction"))
    runtimeOnly(project(":standard-capabilities:realm"))
    runtimeOnly(project(":standard-capabilities:standard-contracts"))

    testImplementation(project(":testkit:substrate-testkit"))
}

tasks.named("check") {
    dependsOn(tasks.named("installDist"))
}

val serviceLauncherImageContext by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the Docker build context for the Fulcrum service launcher image."
    dependsOn(tasks.named("installDist"))

    into(layout.buildDirectory.dir("service-launcher-image"))
    from("src/main/resources/fulcrum/container/service-launcher")
    from(layout.buildDirectory.dir("install/fulcrum")) {
        into("fulcrum")
    }
}

val defaultServiceLauncherImage = "ghcr.io/sh-harold/fulcrum-service-launcher:dev"
val serviceLauncherImageTag = providers.gradleProperty("fulcrum.serviceLauncherImage")
    .orElse(defaultServiceLauncherImage)
val kubeContext = providers.gradleProperty("fulcrum.kubeContext")
val agonesChartVersion = providers.gradleProperty("fulcrum.agonesChartVersion")
    .orElse(libs.versions.agones)
val agonesReleaseName = providers.gradleProperty("fulcrum.agonesReleaseName")
    .orElse("agones")
val agonesSystemNamespace = providers.gradleProperty("fulcrum.agonesSystemNamespace")
    .orElse("agones-system")
val lobbyEndpointHost = providers.gradleProperty("fulcrum.lobbyEndpointHost")
val lobbyEndpointPort = providers.gradleProperty("fulcrum.lobbyEndpointPort")
    .orElse("25565")
val lobbyNamespace = providers.gradleProperty("fulcrum.lobbyNamespace")
    .orElse("fulcrum-lobby")
val lobbyVelocityService = providers.gradleProperty("fulcrum.lobbyVelocityService")
    .orElse("fulcrum-velocity-l4")
val lobbyNodeHost = providers.gradleProperty("fulcrum.lobbyNodeHost")
    .orElse("127.0.0.1")
val lobbyAgonesFleetName = providers.gradleProperty("fulcrum.lobbyAgonesFleetName")
    .orElse("fulcrum-lobby-paper")
val verifyLobbyAgonesFleetState = providers.gradleProperty("fulcrum.verifyLobbyAgonesFleetState")
val verifyLobbyScaleOut = providers.gradleProperty("fulcrum.verifyLobbyScaleOut")
    .orElse("true")
val expectedLobbyAgonesAllocatedReplicas = providers.gradleProperty("fulcrum.expectedLobbyAgonesAllocatedReplicas")
    .orElse(verifyLobbyScaleOut.map { value ->
        if (value.equals("true", ignoreCase = true)) "2" else "1"
    })
val lobbyMinecraftProtocolVersion = providers.gradleProperty("fulcrum.minecraftProtocolVersion")
    .orElse("0")
val lobbyLoginUsername = providers.gradleProperty("fulcrum.lobbyLoginUsername")
    .orElse("FulcrumBotOne")
val secondLobbyLoginUsername = providers.gradleProperty("fulcrum.secondLobbyLoginUsername")
    .orElse("FulcrumBotTwo")
val expectedLobbySpawnBlock = providers.gradleProperty("fulcrum.expectedLobbySpawnBlock")
    .orElse("bedrock")
val expectedLobbySpawnWorld = providers.gradleProperty("fulcrum.expectedLobbySpawnWorld")
    .orElse("world")
val expectedLobbyBedrockBlockX = providers.gradleProperty("fulcrum.expectedLobbyBedrockBlockX")
    .orElse("0")
val expectedLobbyBedrockBlockY = providers.gradleProperty("fulcrum.expectedLobbyBedrockBlockY")
    .orElse("64")
val expectedLobbyBedrockBlockZ = providers.gradleProperty("fulcrum.expectedLobbyBedrockBlockZ")
    .orElse("0")
val expectedLobbyPlayerX = providers.gradleProperty("fulcrum.expectedLobbyPlayerX")
    .orElse("0.5")
val expectedLobbyPlayerY = providers.gradleProperty("fulcrum.expectedLobbyPlayerY")
    .orElse("65.0")
val expectedLobbyPlayerZ = providers.gradleProperty("fulcrum.expectedLobbyPlayerZ")
    .orElse("0.5")
val expectedLobbyPlayerYaw = providers.gradleProperty("fulcrum.expectedLobbyPlayerYaw")
    .orElse("0.0")
val expectedLobbyPlayerPitch = providers.gradleProperty("fulcrum.expectedLobbyPlayerPitch")
    .orElse("0.0")
val expectedLobbyDisplayName = providers.gradleProperty("fulcrum.expectedLobbyDisplayName")
    .orElse("Fulcrum Bot One")
val expectedLobbyRankLabel = providers.gradleProperty("fulcrum.expectedLobbyRankLabel")
    .orElse("Admin")
val expectedLobbyDecoratedChatContains = providers.gradleProperty("fulcrum.expectedLobbyDecoratedChatContains")
    .orElse("[Admin] Fulcrum Bot One: fulcrum-proof-chat")
val expectedSecondLobbyDisplayName = providers.gradleProperty("fulcrum.expectedSecondLobbyDisplayName")
    .orElse("Fulcrum Bot Two")
val expectedSecondLobbyRankLabel = providers.gradleProperty("fulcrum.expectedSecondLobbyRankLabel")
    .orElse("Admin")
val expectedSecondLobbyDecoratedChatContains = providers.gradleProperty("fulcrum.expectedSecondLobbyDecoratedChatContains")
    .orElse("[Admin] Fulcrum Bot Two: fulcrum-proof-chat")
val scaleOutTriggerLobbyLoginUsername = providers.gradleProperty("fulcrum.scaleOutTriggerLobbyLoginUsername")
    .orElse("FulcrumBotThree")
val scaleOutTriggerDeniedLobbyLoginReason = providers.gradleProperty("fulcrum.scaleOutTriggerDeniedLobbyLoginReasonContains")
    .orElse("No lobby route is currently available")
val scaleOutLobbyLoginUsername = providers.gradleProperty("fulcrum.scaleOutLobbyLoginUsername")
    .orElse("FulcrumBotFour")
val expectedScaleOutLobbyDisplayName = providers.gradleProperty("fulcrum.expectedScaleOutLobbyDisplayName")
    .orElse("Fulcrum Bot Four")
val expectedScaleOutLobbyRankLabel = providers.gradleProperty("fulcrum.expectedScaleOutLobbyRankLabel")
    .orElse("Admin")
val expectedScaleOutLobbyDecoratedChatContains = providers.gradleProperty("fulcrum.expectedScaleOutLobbyDecoratedChatContains")
    .orElse("[Admin] Fulcrum Bot Four: fulcrum-proof-chat")
val lobbyScaleOutTimeout = providers.gradleProperty("fulcrum.lobbyScaleOutTimeout")
    .orElse("PT60S")
val lobbyTargetCapacity = providers.gradleProperty("fulcrum.lobbyTargetCapacity")
    .orElse(verifyLobbyScaleOut.map { value ->
        if (value.equals("true", ignoreCase = true)) "1" else "75"
    })
val lobbyHardCapacity = providers.gradleProperty("fulcrum.lobbyHardCapacity")
    .orElse(verifyLobbyScaleOut.map { value ->
        if (value.equals("true", ignoreCase = true)) "2" else "150"
    })
val deniedLobbyLoginUsername = providers.gradleProperty("fulcrum.deniedLobbyLoginUsername")
    .orElse("FulcrumBannedOne")
val deniedLobbyLoginReason = providers.gradleProperty("fulcrum.deniedLobbyLoginReasonContains")
    .orElse("Banned from the lobby")
val lobbyVerifierTimeout = providers.gradleProperty("fulcrum.lobbyVerifierTimeout")
    .orElse("PT10S")

fun kubectlCommand(vararg args: String): List<String> = buildList {
    add("kubectl")
    kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {
        add("--context")
        add(it)
    }
    addAll(args)
}

fun helmCommand(vararg args: String): List<String> = buildList {
    add("helm")
    kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {
        add("--kube-context")
        add(it)
    }
    addAll(args)
}

data class ClusterPreflightCheck(
    val label: String,
    val command: List<String>,
    val recoveryHint: String)

fun runClusterPreflightCheck(check: ClusterPreflightCheck): String? {
    val displayCommand = check.command.joinToString(" ")
    return try {
        val process = ProcessBuilder(check.command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (!finished) {
            process.destroyForcibly()
            """
            |${check.label} timed out while running `$displayCommand`.
            |${check.recoveryHint}
            """.trimMargin()
        } else if (process.exitValue() != 0) {
            """
            |${check.label} failed while running `$displayCommand` with exit code ${process.exitValue()}.
            |${check.recoveryHint}
            |Output:
            |${output.ifBlank { "<no output>" }.prependIndent("  ")}
            """.trimMargin()
        } else {
            null
        }
    } catch (exception: IOException) {
        """
        |${check.label} failed because `${check.command.first()}` is not available on PATH.
        |${check.recoveryHint}
        """.trimMargin()
    }
}

fun runClusterPreflightChecks(checks: List<ClusterPreflightCheck>) {
    val failures = checks.mapNotNull(::runClusterPreflightCheck)
    if (failures.isNotEmpty()) {
        throw GradleException("Cluster preflight failed:\n\n${failures.joinToString("\n\n")}")
    }
}

tasks.register<Exec>("serviceLauncherImage") {
    group = "distribution"
    description = "Builds the Fulcrum service launcher container image from the assembled context."
    dependsOn(serviceLauncherImageContext)
    workingDir(layout.buildDirectory.dir("service-launcher-image"))
    doFirst {
        commandLine("docker", "build", "-t", serviceLauncherImageTag.get(), ".")
    }
}

tasks.named("check") {
    dependsOn(serviceLauncherImageContext)
}

evaluationDependsOn(":host:paper-agent")
val paperAgentJar = project(":host:paper-agent").tasks.named<Jar>("jar")

val paperGameserverImageContext by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the Docker build context for the Fulcrum Paper GameServer image."
    dependsOn(tasks.named("installDist"), paperAgentJar)

    into(layout.buildDirectory.dir("paper-gameserver-image"))
    from("src/main/resources/fulcrum/container/paper-gameserver")
    from(layout.buildDirectory.dir("install/fulcrum")) {
        into("fulcrum")
    }
    from(paperAgentJar) {
        into("plugins")
        rename { "FulcrumPaperAgent.jar" }
    }
}

val defaultPaperGameserverImage = "ghcr.io/sh-harold/fulcrum-paper-gameserver:dev"
val paperGameserverImageTag = providers.gradleProperty("fulcrum.paperGameserverImage")
    .orElse(defaultPaperGameserverImage)

tasks.register<Exec>("paperGameserverImage") {
    group = "distribution"
    description = "Builds the Fulcrum Paper GameServer container image from the assembled context."
    dependsOn(paperGameserverImageContext)
    workingDir(layout.buildDirectory.dir("paper-gameserver-image"))
    doFirst {
        commandLine("docker", "build", "-t", paperGameserverImageTag.get(), ".")
    }
}

evaluationDependsOn(":host:velocity-agent")
val velocityAgentJar = project(":host:velocity-agent").tasks.named<Jar>("jar")

val velocityProxyImageContext by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the Docker build context for the Fulcrum Velocity proxy image."
    dependsOn(tasks.named("installDist"), velocityAgentJar)

    into(layout.buildDirectory.dir("velocity-proxy-image"))
    from("src/main/resources/fulcrum/container/velocity-proxy")
    from(layout.buildDirectory.dir("install/fulcrum")) {
        into("fulcrum")
    }
    from(velocityAgentJar) {
        into("plugins")
        rename { "FulcrumVelocityAgent.jar" }
    }
}

val defaultVelocityProxyImage = "ghcr.io/sh-harold/fulcrum-velocity-proxy:dev"
val velocityProxyImageTag = providers.gradleProperty("fulcrum.velocityProxyImage")
    .orElse(defaultVelocityProxyImage)

tasks.register<Exec>("velocityProxyImage") {
    group = "distribution"
    description = "Builds the Fulcrum Velocity proxy container image from the assembled context."
    dependsOn(velocityProxyImageContext)
    workingDir(layout.buildDirectory.dir("velocity-proxy-image"))
    doFirst {
        commandLine("docker", "build", "-t", velocityProxyImageTag.get(), ".")
    }
}

val lobbyPaperFleetManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/agones/lobby-paper-fleet.yaml")
val renderedLobbyPaperFleetManifest = layout.buildDirectory.file("kubernetes/paper-agones/lobby-paper-fleet.yaml")
val lobbySharedShardAllocationManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/agones/lobby-shared-shard-allocation.yaml")
val renderedLobbySharedShardAllocationManifest =
    layout.buildDirectory.file("kubernetes/paper-agones/lobby-shared-shard-allocation.yaml")
val lobbyVelocityManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/velocity/lobby-velocity.yaml")
val renderedLobbyVelocityManifest = layout.buildDirectory.file("kubernetes/velocity/lobby-velocity.yaml")
val lobbyPaperAllocationManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/agones/lobby-paper-allocation.yaml")
val lobbyNamespaceManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/agones/lobby-namespace.yaml")
val agonesHelmValues = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/agones/agones-helm-values.yaml")
val lobbyKafkaManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/substrate/lobby-kafka.yaml")
val renderedLobbyKafkaManifest = layout.buildDirectory.file("kubernetes/substrate/lobby-kafka.yaml")
val renderedAgonesAllocatorCaSecret = layout.buildDirectory.file("kubernetes/substrate/agones-allocator-ca-secret.yaml")

tasks.register("paperAgonesClusterPreflight") {
    group = "verification"
    description = "Verifies kubectl and Helm can reach the configured Kubernetes cluster before deploying Paper Agones resources."
    doLast {
        runClusterPreflightChecks(listOf(
            ClusterPreflightCheck(
                "Kubernetes context check",
                kubectlCommand("config", "current-context"),
                "Enable Docker Desktop Kubernetes or pass -Pfulcrum.kubeContext=<context> for the target cluster."),
            ClusterPreflightCheck(
                "Kubernetes API reachability check",
                kubectlCommand("version", "--output=yaml"),
                "Verify kubectl can reach the target cluster before running clusterE2e."),
            ClusterPreflightCheck(
                "Helm availability check",
                helmCommand("version", "--short"),
                "Install Helm and ensure `helm` is on PATH before Agones installation."))
        )
    }
}

tasks.register<Exec>("paperAgonesApplyNamespace") {
    group = "deployment"
    description = "Applies the Fulcrum lobby GameServer namespace before installing Agones."
    doFirst {
        commandLine(kubectlCommand("apply", "-f", lobbyNamespaceManifest.asFile.absolutePath))
    }
}

tasks.register<Exec>("paperAgonesInstallAgones") {
    group = "deployment"
    description = "Installs or upgrades Agones with Helm for the Fulcrum lobby GameServer namespace."
    dependsOn(tasks.named("paperAgonesApplyNamespace"))
    doFirst {
        commandLine(helmCommand(
            "upgrade",
            "--install",
            agonesReleaseName.get(),
            "agones/agones",
            "--repo",
            "https://agones.dev/chart/stable",
            "--version",
            agonesChartVersion.get(),
            "--namespace",
            agonesSystemNamespace.get(),
            "--create-namespace",
            "--values",
            agonesHelmValues.asFile.absolutePath,
            "--wait",
            "--timeout",
            "300s"))
    }
}

tasks.register<Sync>("paperAgonesRenderManifests") {
    group = "distribution"
    description = "Renders Paper Agones Kubernetes manifests with the effective container image tags."
    into(layout.buildDirectory.dir("kubernetes/paper-agones"))
    from(lobbyPaperFleetManifest) {
        filter { line: String ->
            line
                .replace(defaultServiceLauncherImage, serviceLauncherImageTag.get())
                .replace(defaultPaperGameserverImage, paperGameserverImageTag.get())
        }
    }
    from(lobbySharedShardAllocationManifest) {
        filter { line: String ->
            line
                .replace(defaultServiceLauncherImage, serviceLauncherImageTag.get())
                .replace(defaultPaperGameserverImage, paperGameserverImageTag.get())
        }
    }
}

tasks.register<Sync>("paperAgonesRenderSubstrateManifests") {
    group = "distribution"
    description = "Renders lobby substrate Kubernetes manifests with the effective service launcher image tag."
    into(layout.buildDirectory.dir("kubernetes/substrate"))
    from(lobbyKafkaManifest) {
        filter { line: String ->
            line.replace(defaultServiceLauncherImage, serviceLauncherImageTag.get())
        }
    }
}

tasks.register("paperAgonesSyncAllocatorCa") {
    group = "deployment"
    description = "Copies the Agones allocator TLS CA into the Fulcrum lobby namespace for controller-service mTLS."
    dependsOn(
        tasks.named("paperAgonesApplyNamespace"),
        tasks.named("paperAgonesInstallAgones"))
    outputs.file(renderedAgonesAllocatorCaSecret)
    doLast {
        val ca = providers.exec {
            commandLine(kubectlCommand(
                "-n",
                agonesSystemNamespace.get(),
                "get",
                "secret",
                "allocator-tls-ca",
                "-o",
                "jsonpath={.data.tls-ca\\.crt}"))
        }.standardOutput.asText.get().trim()
        if (ca.isBlank()) {
            throw GradleException("Agones allocator-tls-ca secret did not contain data.tls-ca.crt")
        }
        val renderedSecret = renderedAgonesAllocatorCaSecret.get().asFile
        renderedSecret.parentFile.mkdirs()
        renderedSecret.writeText("""
            |apiVersion: v1
            |kind: Secret
            |metadata:
            |  name: fulcrum-agones-allocator-tls-ca
            |  namespace: fulcrum-lobby
            |type: Opaque
            |data:
            |  tls-ca.crt: "$ca"
            |""".trimMargin())
        providers.exec {
            commandLine(kubectlCommand("apply", "-f", renderedSecret.absolutePath))
        }.result.get().assertNormalExitValue()
    }
}

tasks.register<Sync>("velocityL4RenderManifests") {
    group = "distribution"
    description = "Renders Velocity L4 Kubernetes manifests with the effective container image tag."
    into(layout.buildDirectory.dir("kubernetes/velocity"))
    from(lobbyVelocityManifest) {
        filter { line: String ->
            line
                .replace(defaultVelocityProxyImage, velocityProxyImageTag.get())
                .replace("FULCRUM_LOBBY_TARGET_CAPACITY: \"75\"",
                    "FULCRUM_LOBBY_TARGET_CAPACITY: \"${lobbyTargetCapacity.get()}\"")
                .replace("FULCRUM_LOBBY_HARD_CAPACITY: \"150\"",
                    "FULCRUM_LOBBY_HARD_CAPACITY: \"${lobbyHardCapacity.get()}\"")
        }
    }
}

tasks.register<Exec>("paperAgonesApplySubstrate") {
    group = "deployment"
    description = "Applies the in-cluster log, store, cache, schema, and authority-service substrate used by the lobby deployment."
    dependsOn(
        tasks.named("serviceLauncherImage"),
        tasks.named("paperAgonesApplyNamespace"),
        tasks.named("paperAgonesRenderSubstrateManifests"))
    doFirst {
        commandLine(kubectlCommand("apply", "-f", renderedLobbyKafkaManifest.get().asFile.absolutePath))
    }
}

tasks.register<Exec>("paperAgonesWaitForKafka") {
    group = "verification"
    description = "Waits for the in-cluster Kafka command-log dependency used by the lobby Paper deployment."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "statefulset/fulcrum-kafka",
            "--timeout=300s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForValkey") {
    group = "verification"
    description = "Waits for the in-cluster Valkey cache dependency used by the Velocity login gate."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "deployment/fulcrum-valkey",
            "--timeout=180s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForPostgres") {
    group = "verification"
    description = "Waits for the in-cluster PostgreSQL record-store dependency used by authority workers."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "statefulset/fulcrum-postgres",
            "--timeout=300s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForCassandra") {
    group = "verification"
    description = "Waits for the in-cluster Cassandra hot-projection dependency used by authority workers."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "statefulset/fulcrum-cassandra",
            "--timeout=600s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForAuthoritySchema") {
    group = "verification"
    description = "Waits for the authority schema provisioner Job to create record-store and hot-projection tables."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "wait",
            "--for=condition=complete",
            "job/fulcrum-authority-schema",
            "--timeout=300s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForAuthorityService") {
    group = "verification"
    description = "Waits for the external authority-service Deployment to become available."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "deployment/fulcrum-authority-service",
            "--timeout=240s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForControllerService") {
    group = "verification"
    description = "Waits for the external controller-service Deployment to become available."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "deployment/fulcrum-controller-service",
            "--timeout=240s"))
    }
}

tasks.register("paperAgonesVerifyAgonesInstall") {
    group = "verification"
    description = "Verifies the Agones CRDs required by the lobby Paper deployment exist."
    doLast {
        providers.exec {
            commandLine(kubectlCommand(
                "get",
                "crd",
                "gameservers.agones.dev",
                "fleets.agones.dev",
                "fleetautoscalers.autoscaling.agones.dev",
                "gameserverallocations.allocation.agones.dev"))
        }.result.get().assertNormalExitValue()
    }
}

tasks.register<Exec>("paperAgonesApply") {
    group = "deployment"
    description = "Applies the Fulcrum lobby Paper Agones resources to the configured Kubernetes cluster."
    dependsOn(
        tasks.named("serviceLauncherImage"),
        tasks.named("paperGameserverImage"),
        tasks.named("paperAgonesRenderManifests"))
    doFirst {
        commandLine(kubectlCommand("apply", "-f", renderedLobbyPaperFleetManifest.get().asFile.absolutePath))
    }
}

tasks.register<Exec>("paperAgonesApplySharedShardAllocation") {
    group = "deployment"
    description = "Applies the typed lobby shared-shard allocation provisioning Jobs after a Paper Fleet replica is Ready."
    dependsOn(
        tasks.named("serviceLauncherImage"),
        tasks.named("paperAgonesRenderManifests"))
    doFirst {
        commandLine(kubectlCommand("apply", "-f", renderedLobbySharedShardAllocationManifest.get().asFile.absolutePath))
    }
}

tasks.register<Exec>("paperAgonesStatus") {
    group = "verification"
    description = "Prints the current Fulcrum lobby Paper Agones resource status from the configured Kubernetes cluster."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "get",
            "services,deployments,statefulsets,jobs,pods,fleets,fleetautoscalers,gameservers,gameserverallocations",
            "-o",
            "wide"))
    }
}

tasks.register<Exec>("paperAgonesWaitForWorldArtifact") {
    group = "verification"
    description = "Waits for the lobby world artifact provisioner Job to complete in the configured Kubernetes cluster."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "wait",
            "--for=condition=complete",
            "job/fulcrum-lobby-world-artifact",
            "--timeout=180s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForCapabilitySeed") {
    group = "verification"
    description = "Waits for the lobby capability seed provisioner Job to publish standard capability commands."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "wait",
            "--for=condition=complete",
            "job/fulcrum-lobby-capability-seed",
            "--timeout=180s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForCapabilityMaterialization") {
    group = "verification"
    description = "Waits for the lobby capability materialization verifier Job to observe standard capability cache writes."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "wait",
            "--for=condition=complete",
            "job/fulcrum-lobby-capability-materialization",
            "--timeout=300s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForFleetReady") {
    group = "verification"
    description = "Waits for the lobby Paper Fleet to report one Ready GameServer."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "wait",
            "--for=jsonpath={.status.readyReplicas}=1",
            "fleet/fulcrum-lobby-paper",
            "--timeout=300s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForSharedShardAllocation") {
    group = "verification"
    description = "Waits for the lobby shared-shard allocation provisioner Job to publish the typed control command."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "wait",
            "--for=condition=complete",
            "job/fulcrum-lobby-shared-shard-allocation",
            "--timeout=180s"))
    }
}

tasks.register<Exec>("paperAgonesWaitForSharedShardAllocationState") {
    group = "verification"
    description = "Waits for controller-service to materialize shared-shard allocation endpoint state."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "wait",
            "--for=condition=complete",
            "job/fulcrum-lobby-shared-shard-allocation-materialization",
            "--timeout=300s"))
    }
}

tasks.register("paperAgonesAllocateLobby") {
    group = "verification"
    description = "Creates a GameServerAllocation for the lobby Paper Fleet and verifies Agones allocated a GameServer."
    doLast {
        val allocationResource = providers.exec {
            commandLine(kubectlCommand(
                "-n",
                "fulcrum-lobby",
                "create",
                "-f",
                lobbyPaperAllocationManifest.asFile.absolutePath,
                "-o",
                "name"))
        }.standardOutput.asText.get().trim()
        if (!allocationResource.startsWith("gameserverallocation.allocation.agones.dev/")) {
            throw GradleException("Expected GameServerAllocation resource name, got '$allocationResource'")
        }

        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                "fulcrum-lobby",
                "wait",
                "--for=jsonpath={.status.state}=Allocated",
                allocationResource,
                "--timeout=60s"))
        }.result.get().assertNormalExitValue()

        val gameServerName = providers.exec {
            commandLine(kubectlCommand(
                "-n",
                "fulcrum-lobby",
                "get",
                allocationResource,
                "-o",
                "jsonpath={.status.gameServerName}"))
        }.standardOutput.asText.get().trim()
        if (gameServerName.isBlank()) {
            throw GradleException("GameServerAllocation $allocationResource reached Allocated without status.gameServerName")
        }
        logger.lifecycle("Agones allocated lobby Paper GameServer: $gameServerName")
    }
}

tasks.register<Exec>("velocityL4Apply") {
    group = "deployment"
    description = "Applies the Fulcrum Velocity proxy and L4 Service resources to the configured Kubernetes cluster."
    dependsOn(
        tasks.named("paperAgonesApplyNamespace"),
        tasks.named("velocityProxyImage"),
        tasks.named("velocityL4RenderManifests"))
    doFirst {
        commandLine(kubectlCommand("apply", "-f", renderedLobbyVelocityManifest.get().asFile.absolutePath))
    }
}

tasks.register<Exec>("velocityL4Status") {
    group = "verification"
    description = "Prints the current Fulcrum Velocity proxy and L4 Service status from the configured Kubernetes cluster."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "get",
            "deployments,services,pods",
            "-l",
            "sh.harold.fulcrum/role=velocity-agent",
            "-o",
            "wide"))
    }
}

tasks.register("paperAgonesPhase2Deploy") {
    group = "deployment"
    description = "Builds images, applies lobby Paper Agones resources, verifies readiness, requests controller-owned lobby allocation, and prints cluster status."
    dependsOn(
        tasks.named("paperAgonesClusterPreflight"),
        tasks.named("paperAgonesApplyNamespace"),
        tasks.named("paperAgonesInstallAgones"),
        tasks.named("paperAgonesSyncAllocatorCa"),
        tasks.named("paperAgonesApplySubstrate"),
        tasks.named("paperAgonesWaitForKafka"),
        tasks.named("paperAgonesWaitForValkey"),
        tasks.named("paperAgonesWaitForPostgres"),
        tasks.named("paperAgonesWaitForCassandra"),
        tasks.named("paperAgonesWaitForAuthoritySchema"),
        tasks.named("paperAgonesWaitForAuthorityService"),
        tasks.named("paperAgonesWaitForControllerService"),
        tasks.named("paperAgonesVerifyAgonesInstall"),
        tasks.named("paperAgonesApply"),
        tasks.named("paperAgonesWaitForWorldArtifact"),
        tasks.named("paperAgonesWaitForCapabilitySeed"),
        tasks.named("paperAgonesWaitForCapabilityMaterialization"),
        tasks.named("paperAgonesWaitForFleetReady"),
        tasks.named("paperAgonesApplySharedShardAllocation"),
        tasks.named("paperAgonesWaitForSharedShardAllocation"),
        tasks.named("paperAgonesWaitForSharedShardAllocationState"),
        tasks.named("paperAgonesStatus"))
}

tasks.register("paperAgonesPhase3Deploy") {
    group = "deployment"
    description = "Builds images, deploys the lobby Paper Agones slice, applies Velocity L4 ingress resources, and prints cluster status."
    dependsOn(
        tasks.named("paperAgonesPhase2Deploy"),
        tasks.named("velocityL4Apply"),
        tasks.named("velocityL4Status"))
}

tasks.register<JavaExec>("lobbyClusterE2eVerify") {
    group = "verification"
    description = "Resolves the Velocity L4 endpoint and verifies Minecraft status, lobby proof, scale-out, and login denial."
    dependsOn(tasks.named("paperAgonesPhase3Deploy"))
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("sh.harold.fulcrum.distribution.launcher.LobbyClusterE2eVerifier")
    doFirst {
        val runArgs = mutableListOf(
            "--endpoint-port=${lobbyEndpointPort.get()}",
            "--namespace=${lobbyNamespace.get()}",
            "--service=${lobbyVelocityService.get()}",
            "--node-host=${lobbyNodeHost.get()}",
            "--agones-fleet-name=${lobbyAgonesFleetName.get()}",
            "--expected-agones-allocated-replicas=${expectedLobbyAgonesAllocatedReplicas.get()}",
            "--protocol-version=${lobbyMinecraftProtocolVersion.get()}",
            "--login-username=${lobbyLoginUsername.get()}",
            "--second-login-username=${secondLobbyLoginUsername.get()}",
            "--expected-lobby-spawn-block=${expectedLobbySpawnBlock.get()}",
            "--expected-lobby-spawn-world=${expectedLobbySpawnWorld.get()}",
            "--expected-lobby-bedrock-block-x=${expectedLobbyBedrockBlockX.get()}",
            "--expected-lobby-bedrock-block-y=${expectedLobbyBedrockBlockY.get()}",
            "--expected-lobby-bedrock-block-z=${expectedLobbyBedrockBlockZ.get()}",
            "--expected-lobby-player-x=${expectedLobbyPlayerX.get()}",
            "--expected-lobby-player-y=${expectedLobbyPlayerY.get()}",
            "--expected-lobby-player-z=${expectedLobbyPlayerZ.get()}",
            "--expected-lobby-player-yaw=${expectedLobbyPlayerYaw.get()}",
            "--expected-lobby-player-pitch=${expectedLobbyPlayerPitch.get()}",
            "--expected-lobby-display-name=${expectedLobbyDisplayName.get()}",
            "--expected-lobby-rank-label=${expectedLobbyRankLabel.get()}",
            "--expected-lobby-decorated-chat-contains=${expectedLobbyDecoratedChatContains.get()}",
            "--expected-second-lobby-display-name=${expectedSecondLobbyDisplayName.get()}",
            "--expected-second-lobby-rank-label=${expectedSecondLobbyRankLabel.get()}",
            "--expected-second-lobby-decorated-chat-contains=${expectedSecondLobbyDecoratedChatContains.get()}",
            "--verify-scale-out=${verifyLobbyScaleOut.get()}",
            "--scale-out-trigger-login-username=${scaleOutTriggerLobbyLoginUsername.get()}",
            "--scale-out-trigger-denied-reason-contains=${scaleOutTriggerDeniedLobbyLoginReason.get()}",
            "--scale-out-login-username=${scaleOutLobbyLoginUsername.get()}",
            "--expected-scale-out-lobby-display-name=${expectedScaleOutLobbyDisplayName.get()}",
            "--expected-scale-out-lobby-rank-label=${expectedScaleOutLobbyRankLabel.get()}",
            "--expected-scale-out-lobby-decorated-chat-contains=${expectedScaleOutLobbyDecoratedChatContains.get()}",
            "--scale-out-timeout=${lobbyScaleOutTimeout.get()}",
            "--timeout=${lobbyVerifierTimeout.get()}")
        lobbyEndpointHost.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--endpoint-host=$it")
        }
        kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--kube-context=$it")
        }
        verifyLobbyAgonesFleetState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-agones-fleet-state=$it")
        }
        deniedLobbyLoginUsername.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--denied-login-username=$it")
        }
        deniedLobbyLoginReason.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--denied-login-reason-contains=$it")
        }
        setArgs(runArgs)
    }
}

tasks.named("paperAgonesApply") {
    mustRunAfter(tasks.named("paperAgonesVerifyAgonesInstall"))
    mustRunAfter(tasks.named("paperAgonesWaitForKafka"))
    mustRunAfter(tasks.named("paperAgonesWaitForValkey"))
    mustRunAfter(tasks.named("paperAgonesWaitForAuthorityService"))
    mustRunAfter(tasks.named("paperAgonesWaitForControllerService"))
}

tasks.named("paperAgonesStatus") {
    mustRunAfter(tasks.named("paperAgonesWaitForSharedShardAllocationState"))
}

tasks.named("paperAgonesWaitForWorldArtifact") {
    mustRunAfter(tasks.named("paperAgonesApply"))
}

tasks.named("paperAgonesWaitForCapabilitySeed") {
    mustRunAfter(tasks.named("paperAgonesApply"))
    mustRunAfter(tasks.named("paperAgonesWaitForWorldArtifact"))
}

tasks.named("paperAgonesWaitForFleetReady") {
    mustRunAfter(tasks.named("paperAgonesWaitForCapabilityMaterialization"))
}

tasks.named("paperAgonesApplySharedShardAllocation") {
    mustRunAfter(tasks.named("paperAgonesWaitForFleetReady"))
}

tasks.named("paperAgonesWaitForSharedShardAllocation") {
    mustRunAfter(tasks.named("paperAgonesApplySharedShardAllocation"))
}

tasks.named("paperAgonesWaitForSharedShardAllocationState") {
    mustRunAfter(tasks.named("paperAgonesWaitForSharedShardAllocation"))
}

tasks.named("paperAgonesAllocateLobby") {
    mustRunAfter(tasks.named("paperAgonesWaitForFleetReady"))
}

tasks.named("paperAgonesApplyNamespace") {
    mustRunAfter(tasks.named("paperAgonesClusterPreflight"))
}

tasks.named("paperAgonesInstallAgones") {
    mustRunAfter(tasks.named("paperAgonesApplyNamespace"))
}

tasks.named("paperAgonesVerifyAgonesInstall") {
    mustRunAfter(tasks.named("paperAgonesInstallAgones"))
}

tasks.named("paperAgonesApplySubstrate") {
    mustRunAfter(tasks.named("paperAgonesApplyNamespace"))
    mustRunAfter(tasks.named("paperAgonesSyncAllocatorCa"))
}

tasks.named("paperAgonesWaitForKafka") {
    mustRunAfter(tasks.named("paperAgonesApplySubstrate"))
}

tasks.named("paperAgonesWaitForValkey") {
    mustRunAfter(tasks.named("paperAgonesApplySubstrate"))
}

tasks.named("paperAgonesWaitForPostgres") {
    mustRunAfter(tasks.named("paperAgonesApplySubstrate"))
}

tasks.named("paperAgonesWaitForCassandra") {
    mustRunAfter(tasks.named("paperAgonesApplySubstrate"))
}

tasks.named("paperAgonesWaitForAuthoritySchema") {
    mustRunAfter(tasks.named("paperAgonesWaitForPostgres"))
    mustRunAfter(tasks.named("paperAgonesWaitForCassandra"))
}

tasks.named("paperAgonesWaitForAuthorityService") {
    mustRunAfter(tasks.named("paperAgonesWaitForKafka"))
    mustRunAfter(tasks.named("paperAgonesWaitForValkey"))
    mustRunAfter(tasks.named("paperAgonesWaitForAuthoritySchema"))
}

tasks.named("paperAgonesWaitForControllerService") {
    mustRunAfter(tasks.named("paperAgonesWaitForKafka"))
    mustRunAfter(tasks.named("paperAgonesWaitForAuthoritySchema"))
    mustRunAfter(tasks.named("paperAgonesWaitForAuthorityService"))
}

tasks.named("paperAgonesWaitForCapabilityMaterialization") {
    mustRunAfter(tasks.named("paperAgonesWaitForCapabilitySeed"))
}

tasks.named("velocityL4Apply") {
    mustRunAfter(tasks.named("paperAgonesPhase2Deploy"))
}

tasks.named("velocityL4Status") {
    mustRunAfter(tasks.named("velocityL4Apply"))
}

tasks.named("lobbyClusterE2eVerify") {
    mustRunAfter(tasks.named("paperAgonesPhase3Deploy"))
}

tasks.named("check") {
    dependsOn(paperGameserverImageContext)
    dependsOn(velocityProxyImageContext)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    reports.html.required.set(false)
}
