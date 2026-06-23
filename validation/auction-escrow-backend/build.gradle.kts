import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.jvm.tasks.Jar
import java.io.File
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

val defaultAuctionEscrowImage = "ghcr.io/harolddotsh/fulcrum-auction-escrow:dev"
val auctionEscrowImageTag = providers.gradleProperty("fulcrum.auctionEscrowImage")
    .orElse(defaultAuctionEscrowImage)
val auctionEscrowPublishedImageTag = providers.gradleProperty("fulcrum.auctionEscrowPublishedImage")
    .orElse("ghcr.io/harolddotsh/fulcrum-auction-escrow:${project.version}")
val auctionEscrowImagePinnedRefFile =
    layout.buildDirectory.file("publication/images/auction-escrow-backend.ref")

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

val auctionEscrowImagePublishTag by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Tags the auction escrow backend image with its GHCR publication ref."
    dependsOn(tasks.named("auctionEscrowImage"))
    doFirst {
        commandLine("docker", "tag", auctionEscrowImageTag.get(), auctionEscrowPublishedImageTag.get())
    }
}

val auctionEscrowImagePush by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Pushes the auction escrow backend OCI image to GHCR."
    dependsOn(auctionEscrowImagePublishTag)
    doFirst {
        commandLine("docker", "push", auctionEscrowPublishedImageTag.get())
    }
}

val auctionEscrowImageSign by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Signs the pushed auction escrow backend OCI image with cosign."
    dependsOn(auctionEscrowImagePush)
    doFirst {
        commandLine("cosign", "sign", "--yes", auctionEscrowPublishedImageTag.get())
    }
}

val auctionEscrowImagePin by tasks.registering {
    group = "publishing"
    description = "Resolves and records the digest-pinned auction escrow backend image reference."
    dependsOn(auctionEscrowImageSign)
    outputs.file(auctionEscrowImagePinnedRefFile)

    doLast {
        val execution = providers.exec {
            commandLine("oras", "resolve", auctionEscrowPublishedImageTag.get())
        }
        execution.result.get().assertNormalExitValue()
        val digest = execution.standardOutput.asText.get().trim()
        if (!digest.startsWith("sha256:")) {
            throw GradleException("oras resolve did not return a sha256 digest for ${auctionEscrowPublishedImageTag.get()}")
        }
        val outputFile = auctionEscrowImagePinnedRefFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText("""
            |schema=fulcrum.image-publish-receipt/v1
            |image=auction-escrow-backend
            |publishedRef=${auctionEscrowPublishedImageTag.get()}
            |digest=$digest
            |pinnedRef=${auctionEscrowPublishedImageTag.get()}@$digest
            |signature=cosign
            |""".trimMargin())
    }
}

tasks.register("publishAuctionEscrowImage") {
    group = "publishing"
    description = "Builds and pushes the auction escrow backend OCI image."
    dependsOn(auctionEscrowImagePush)
}

tasks.register("signAuctionEscrowImage") {
    group = "publishing"
    description = "Builds, pushes, cosign-signs, and pins the auction escrow backend OCI image."
    dependsOn(auctionEscrowImagePin)
}

evaluationDependsOn(":validation:auction-experience-bundle")
val auctionExperienceProject = project(":validation:auction-experience-bundle")
val auctionExperienceBundleJar = auctionExperienceProject.tasks.named<Jar>("jar")
val auctionEscrowBundlePublishedRef = providers.gradleProperty("fulcrum.auctionEscrowBundle")
    .orElse("ghcr.io/harolddotsh/fulcrum-bundles/auction-escrow:${project.version}")
val auctionEscrowBundleManifestFile =
    layout.buildDirectory.file("publication/bundles/auction-escrow/bundle-publication.json")
val auctionEscrowBundlePinnedRefFile =
    layout.buildDirectory.file("publication/bundles/auction-escrow/auction-escrow-bundle.ref")
val auctionEscrowBackendImageRefOverride = providers.gradleProperty("fulcrum.auctionEscrowBackendImageRef")

val writeAuctionEscrowBundleManifest by tasks.registering {
    group = "publishing"
    description = "Writes the digest-pinned auction escrow bundle publication manifest."
    dependsOn(auctionEscrowImagePin)
    inputs.property("auctionEscrowBackendImageRef", auctionEscrowBackendImageRefOverride.orElse(""))
    inputs.file(auctionEscrowImagePinnedRefFile)
    outputs.file(auctionEscrowBundleManifestFile)

    doLast {
        val backendImageRef = auctionEscrowBackendImageRefOverride.orNull
            ?.takeIf { it.isNotBlank() }
            ?: receiptValue(auctionEscrowImagePinnedRefFile.get().asFile, "pinnedRef")
        if (!backendImageRef.contains("@sha256:")) {
            throw GradleException("fulcrum.auctionEscrowBackendImageRef must be pinned by digest")
        }
        val outputFile = auctionEscrowBundleManifestFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText("""
            |{
            |  "schema": "fulcrum.bundle-publication/v1",
            |  "bundle": "auction-escrow",
            |  "kind": "authority-backend",
            |  "backendImageRef": ${jsonQuote(backendImageRef)},
            |  "providerJar": "providers/auction-experience-bundle.jar",
            |  "migrations": "migrations/auction-escrow"
            |}
            |""".trimMargin())
    }
}

val auctionEscrowBundleArtifact by tasks.registering(Tar::class) {
    group = "publishing"
    description = "Assembles the auction escrow OCI bundle payload tar."
    dependsOn(writeAuctionEscrowBundleManifest, auctionExperienceBundleJar)
    archiveFileName.set("auction-escrow-bundle.tar")
    destinationDirectory.set(layout.buildDirectory.dir("publication/bundles/auction-escrow"))
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.FAIL

    from(auctionExperienceBundleJar.flatMap { it.archiveFile }) {
        into("providers")
        rename { "auction-experience-bundle.jar" }
    }
    from(auctionEscrowBundleManifestFile) {
        into("META-INF/fulcrum")
    }
    from(layout.projectDirectory.dir("src/main/resources/fulcrum/migrations/auction-escrow")) {
        into("migrations/auction-escrow")
    }
}

val auctionEscrowBundleArchive = auctionEscrowBundleArtifact.flatMap { it.archiveFile }

val auctionEscrowBundlePush by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Pushes the auction escrow OCI bundle artifact to the configured registry."
    dependsOn(auctionEscrowBundleArtifact)
    inputs.file(auctionEscrowBundleArchive)
    doFirst {
        workingDir(auctionEscrowBundleArchive.get().asFile.parentFile)
        commandLine(
            "oras",
            "push",
            "--artifact-type",
            "application/vnd.harold.fulcrum.bundle.v1",
            auctionEscrowBundlePublishedRef.get(),
            auctionEscrowBundleArchive.get().asFile.name
                + ":application/vnd.harold.fulcrum.bundle.layer.v1+tar")
    }
}

val auctionEscrowBundleSign by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Signs the pushed auction escrow OCI bundle artifact with cosign."
    dependsOn(auctionEscrowBundlePush)
    doFirst {
        commandLine("cosign", "sign", "--yes", auctionEscrowBundlePublishedRef.get())
    }
}

val auctionEscrowBundlePin by tasks.registering {
    group = "publishing"
    description = "Resolves and records the digest-pinned auction escrow OCI bundle reference."
    dependsOn(auctionEscrowBundleSign)
    outputs.file(auctionEscrowBundlePinnedRefFile)

    doLast {
        val execution = providers.exec {
            commandLine("oras", "resolve", auctionEscrowBundlePublishedRef.get())
        }
        execution.result.get().assertNormalExitValue()
        val digest = execution.standardOutput.asText.get().trim()
        if (!digest.startsWith("sha256:")) {
            throw GradleException("oras resolve did not return a sha256 digest for ${auctionEscrowBundlePublishedRef.get()}")
        }
        val outputFile = auctionEscrowBundlePinnedRefFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText("""
            |schema=fulcrum.bundle-publish-receipt/v1
            |bundle=auction-escrow
            |publishedRef=${auctionEscrowBundlePublishedRef.get()}
            |digest=$digest
            |pinnedRef=oci://${auctionEscrowBundlePublishedRef.get()}@$digest
            |signature=cosign
            |""".trimMargin())
    }
}

tasks.register("assembleFulcrumBundles") {
    group = "publishing"
    description = "Assembles Fulcrum v2 OCI bundle payloads."
    dependsOn(auctionEscrowBundleArtifact)
}

tasks.register("publishFulcrumBundles") {
    group = "publishing"
    description = "Assembles and pushes Fulcrum v2 OCI bundle artifacts."
    dependsOn(auctionEscrowBundlePush)
}

tasks.register("signFulcrumBundles") {
    group = "publishing"
    description = "Assembles, pushes, cosign-signs, and pins Fulcrum v2 OCI bundle artifacts."
    dependsOn(auctionEscrowBundlePin)
}

fun jsonQuote(value: String): String {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""
}

fun receiptValue(file: File, key: String): String =
    file.readLines()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.takeIf { it.isNotBlank() }
        ?: throw GradleException("${file.absolutePath} is missing $key")

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
