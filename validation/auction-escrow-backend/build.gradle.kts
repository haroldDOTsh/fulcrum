import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import java.util.concurrent.TimeUnit

plugins {
    `java-library`
    application
}

application {
    applicationName = "auction-escrow-backend"
    mainClass.set("sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowBackendMain")
}

dependencies {
    implementation(project(":data:store-cassandra"))
    implementation(project(":data:store-kafka"))
    implementation(project(":data:store-postgresql"))
    implementation(project(":data:store-valkey"))
    api(project(":sdk:authority-sdk"))
    api(project(":validation:auction-escrow-contract"))
    testImplementation(project(":control:capability-backend-registration"))
    testImplementation(project(":testkit:substrate-testkit"))
    testImplementation(project(":validation:auction-experience-bundle"))
}

val defaultAuctionEscrowImage = "ghcr.io/sh-harold/fulcrum-auction-escrow:dev"
val auctionEscrowImageTag = providers.gradleProperty("fulcrum.auctionEscrowImage")
    .orElse(defaultAuctionEscrowImage)

val auctionEscrowImageContext by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the Docker build context for the auction escrow backend image."
    dependsOn(tasks.named("installDist"))

    into(layout.buildDirectory.dir("auction-escrow-image"))
    from("src/main/resources/fulcrum/container/auction-escrow")
    from(layout.buildDirectory.dir("install/auction-escrow-backend")) {
        into("auction-escrow-backend")
    }
}

tasks.register<Exec>("auctionEscrowImage") {
    group = "distribution"
    description = "Builds the auction escrow backend container image from the assembled context."
    dependsOn(auctionEscrowImageContext)
    workingDir(layout.buildDirectory.dir("auction-escrow-image"))
    doFirst {
        commandLine("docker", "build", "-t", auctionEscrowImageTag.get(), ".")
    }
}

val auctionEscrowManifest = layout.projectDirectory.file(
    "src/main/resources/fulcrum/kubernetes/auction-escrow/auction-escrow.yaml")
val renderedAuctionEscrowManifest =
    layout.buildDirectory.file("kubernetes/auction-escrow/auction-escrow.yaml")

tasks.register<Sync>("auctionEscrowRenderManifests") {
    group = "distribution"
    description = "Renders auction escrow Kubernetes manifests with the effective container image tag."
    inputs.property("auctionEscrowImageTag", auctionEscrowImageTag)
    into(layout.buildDirectory.dir("kubernetes/auction-escrow"))
    from(auctionEscrowManifest) {
        filter { line: String ->
            line.replace(defaultAuctionEscrowImage, auctionEscrowImageTag.get())
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
val auctionEscrowNamespace = providers.gradleProperty("fulcrum.auctionEscrowNamespace")
    .orElse(providers.gradleProperty("fulcrum.lobbyNamespace"))
    .orElse("fulcrum-lobby")
val auctionEscrowWaitTimeout = providers.gradleProperty("fulcrum.auctionEscrowWaitTimeout")
    .orElse("180s")
val auctionEscrowSelector = "sh.harold.fulcrum/role=auction-escrow-backend"
val auctionEscrowDeployment = "deployment/fulcrum-auction-escrow"
val auctionEscrowReadyFile = "/var/run/fulcrum/auction-escrow.ready"
val auctionEscrowClusterProofFile =
    layout.buildDirectory.file("cluster-e2e/auction-escrow/auction-escrow-restart-proof.txt")

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

fun timeoutSeconds(timeout: String): Long {
    val trimmed = timeout.trim().lowercase()
    return when {
        trimmed.endsWith("ms") -> (trimmed.removeSuffix("ms").toLong() + 999L) / 1_000L
        trimmed.endsWith("s") -> trimmed.removeSuffix("s").toLong()
        trimmed.endsWith("m") -> trimmed.removeSuffix("m").toLong() * 60L
        else -> trimmed.toLong()
    }.coerceAtLeast(1L)
}

fun waitForAuctionEscrowKubectl(
    label: String,
    args: List<String>,
    timeoutSeconds: Long,
    pollSeconds: Long = 5) {
    val startedAt = System.nanoTime()
    val timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds)
    var attempt = 1
    var lastOutput = ""
    while (System.nanoTime() - startedAt < timeoutNanos) {
        val execution = providers.exec {
            commandLine(kubectlCommand(*args.toTypedArray()))
            isIgnoreExitValue = true
        }
        val result = execution.result.get()
        lastOutput = (execution.standardOutput.asText.get() + execution.standardError.asText.get()).trim()
        if (result.exitValue == 0) {
            logger.lifecycle("$label is ready")
            return
        }
        val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)
        val detail = lastOutput
            .lineSequence()
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?: "exit ${result.exitValue}"
        logger.lifecycle("$label not ready yet after ${elapsedSeconds}s (attempt $attempt, timeout ${timeoutSeconds}s): $detail")
        attempt += 1
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(pollSeconds))
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleException("Interrupted while waiting for $label.", exception)
        }
    }
    val formattedOutput = lastOutput.ifBlank { "<no output>" }.prependIndent("  ")
    throw GradleException(
        "$label did not become ready within ${timeoutSeconds}s."
            + System.lineSeparator()
            + "Last output:"
            + System.lineSeparator()
            + formattedOutput)
}

fun generatedClusterProvider(): String = clusterProvider.get().lowercase()

tasks.register<Exec>("auctionEscrowClusterImportImage") {
    group = "deployment"
    description = "Imports the locally built auction escrow image into the configured generated k3d/kind cluster."
    dependsOn(tasks.named("auctionEscrowImage"))
    doFirst {
        when (val provider = generatedClusterProvider()) {
            "k3d" -> commandLine("k3d", "image", "import", auctionEscrowImageTag.get(), "--cluster", clusterName.get())
            "kind" -> commandLine("kind", "load", "docker-image", "--name", clusterName.get(), auctionEscrowImageTag.get())
            else -> throw GradleException("Unsupported cluster provider `$provider`; expected k3d or kind.")
        }
    }
}

tasks.register<Exec>("auctionEscrowClusterApply") {
    group = "deployment"
    description = "Applies the rendered auction escrow backend manifests to a prepared Fulcrum cluster."
    dependsOn(tasks.named("auctionEscrowRenderManifests"))
    doFirst {
        commandLine(kubectlCommand("apply", "-f", renderedAuctionEscrowManifest.get().asFile.absolutePath))
    }
}

tasks.register("auctionEscrowClusterWaitForInitialized") {
    group = "verification"
    description = "Waits for the auction escrow pod to initialize; readiness stays gated by registration/replay evidence."
    doLast {
        waitForAuctionEscrowKubectl(
            "auction escrow pod initialization",
            listOf(
                "-n",
                auctionEscrowNamespace.get(),
                "wait",
                "--for=condition=Initialized",
                "pod",
                "-l",
                auctionEscrowSelector,
                "--timeout=10s"),
            timeoutSeconds(auctionEscrowWaitTimeout.get()))
    }
}

tasks.register("auctionEscrowClusterWaitForReady") {
    group = "verification"
    description = "Waits for the auction escrow Deployment to publish store-backed readiness evidence."
    doLast {
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                auctionEscrowNamespace.get(),
                "wait",
                "--for=condition=Available",
                auctionEscrowDeployment,
                "--timeout=${auctionEscrowWaitTimeout.get()}"))
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                auctionEscrowNamespace.get(),
                "wait",
                "--for=condition=Ready",
                "pod",
                "-l",
                auctionEscrowSelector,
                "--timeout=${auctionEscrowWaitTimeout.get()}"))
        }.result.get().assertNormalExitValue()
    }
}

tasks.register<Exec>("auctionEscrowClusterStatus") {
    group = "verification"
    description = "Prints auction escrow backend Deployment and Pod status from the configured cluster."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            auctionEscrowNamespace.get(),
            "get",
            "deployment,pods",
            "-l",
            auctionEscrowSelector,
            "-o",
            "wide"))
    }
}

tasks.register("auctionEscrowClusterRestartProof") {
    group = "verification"
    description = "Deletes the ready auction escrow pod and records before/after readiness evidence from the recreated pod."
    dependsOn(tasks.named("auctionEscrowClusterDeploy"))

    doLast {
        fun runKubectl(action: String, args: List<String>) {
            providers.exec {
                commandLine(kubectlCommand(*args.toTypedArray()))
            }.result.get().assertNormalExitValue()
            logger.lifecycle("auction-escrow cluster proof: $action")
        }

        fun kubectlOutput(args: List<String>): String {
            val execution = providers.exec {
                commandLine(kubectlCommand(*args.toTypedArray()))
            }
            execution.result.get().assertNormalExitValue()
            return execution.standardOutput.asText.get().trim()
        }

        fun podField(field: String): String {
            return kubectlOutput(listOf(
                "-n",
                auctionEscrowNamespace.get(),
                "get",
                "pod",
                "-l",
                auctionEscrowSelector,
                "-o",
                "jsonpath={.items[0].metadata.$field}"))
        }

        fun readyEvidence(podName: String): String {
            return kubectlOutput(listOf(
                "-n",
                auctionEscrowNamespace.get(),
                "exec",
                podName,
                "--",
                "cat",
                auctionEscrowReadyFile))
        }

        fun requireReadyEvidence(label: String, evidence: String) {
            listOf(
                "schema=auction-escrow-readiness/v1",
                "status=ready",
                "runtimeStatus=ACCEPTED",
                "replayed=false",
                "evidenceDigest=")
                .filterNot(evidence::contains)
                .takeIf { it.isNotEmpty() }
                ?.let { missing ->
                    throw GradleException("$label auction escrow readiness evidence is missing $missing")
                }
        }

        runKubectl("waited for ready pod before restart", listOf(
            "-n",
            auctionEscrowNamespace.get(),
            "wait",
            "--for=condition=Ready",
            "pod",
            "-l",
            auctionEscrowSelector,
            "--timeout=${auctionEscrowWaitTimeout.get()}"))

        val beforePod = podField("name")
        val beforeUid = podField("uid")
        val beforeEvidence = readyEvidence(beforePod)
        requireReadyEvidence("before restart", beforeEvidence)

        runKubectl("deleted pod $beforePod", listOf(
            "-n",
            auctionEscrowNamespace.get(),
            "delete",
            "pod",
            beforePod,
            "--wait=true",
            "--timeout=${auctionEscrowWaitTimeout.get()}"))
        runKubectl("waited for replacement deployment availability", listOf(
            "-n",
            auctionEscrowNamespace.get(),
            "wait",
            "--for=condition=Available",
            auctionEscrowDeployment,
            "--timeout=${auctionEscrowWaitTimeout.get()}"))
        runKubectl("waited for replacement ready pod", listOf(
            "-n",
            auctionEscrowNamespace.get(),
            "wait",
            "--for=condition=Ready",
            "pod",
            "-l",
            auctionEscrowSelector,
            "--timeout=${auctionEscrowWaitTimeout.get()}"))

        val afterPod = podField("name")
        val afterUid = podField("uid")
        if (beforeUid == afterUid || beforePod == afterPod) {
            throw GradleException("auction escrow restart proof did not observe a replacement pod")
        }
        val afterEvidence = readyEvidence(afterPod)
        requireReadyEvidence("after restart", afterEvidence)

        val proof = auctionEscrowClusterProofFile.get().asFile
        proof.parentFile.mkdirs()
        proof.writeText("""
            |schema=auction-escrow-cluster-restart-proof/v1
            |namespace=${auctionEscrowNamespace.get()}
            |selector=$auctionEscrowSelector
            |deployment=$auctionEscrowDeployment
            |beforePod=$beforePod
            |beforeUid=$beforeUid
            |afterPod=$afterPod
            |afterUid=$afterUid
            |readyFile=$auctionEscrowReadyFile
            |beforeEvidence:
            |${beforeEvidence.prependIndent("  ")}
            |afterEvidence:
            |${afterEvidence.prependIndent("  ")}
            |""".trimMargin())
        logger.lifecycle("Wrote auction escrow cluster restart proof to ${proof.absolutePath}")
    }
}

tasks.register("auctionEscrowClusterDeploy") {
    group = "verification"
    description = "Builds/imports/applies the auction escrow backend and waits for store-backed readiness in a prepared cluster."
    dependsOn(
        tasks.named("auctionEscrowClusterImportImage"),
        tasks.named("auctionEscrowClusterApply"),
        tasks.named("auctionEscrowClusterWaitForInitialized"),
        tasks.named("auctionEscrowClusterWaitForReady"),
        tasks.named("auctionEscrowClusterStatus"))
}

tasks.named("auctionEscrowClusterApply") {
    mustRunAfter(tasks.named("auctionEscrowClusterImportImage"))
}

tasks.named("auctionEscrowClusterWaitForInitialized") {
    mustRunAfter(tasks.named("auctionEscrowClusterApply"))
}

tasks.named("auctionEscrowClusterStatus") {
    mustRunAfter(tasks.named("auctionEscrowClusterWaitForReady"))
}

tasks.named("auctionEscrowClusterWaitForReady") {
    mustRunAfter(tasks.named("auctionEscrowClusterWaitForInitialized"))
}

tasks.named("check") {
    dependsOn(auctionEscrowImageContext)
    dependsOn(tasks.named("auctionEscrowRenderManifests"))
}
