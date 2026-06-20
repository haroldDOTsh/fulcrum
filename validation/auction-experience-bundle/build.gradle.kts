import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    `java-library`
}

dependencies {
    api(project(":host:host-api"))
    api(project(":sdk:authority-sdk"))
    api(project(":validation:auction-escrow-contract"))
    testImplementation(project(":host:paper-agent"))
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    inputs.files(configurations.runtimeClasspath)
        .withPropertyName("bundleRuntimeClasspath")
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read = input.read(buffer)
        while (read != -1) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

val auctionExperienceBundleJar = tasks.named<Jar>("jar")
val auctionExperiencePaperBundleDeclarationFile =
    layout.buildDirectory.file("generated/auction-experience-paper-bundle/auction-experience-bundle.properties")

val auctionExperiencePaperBundleDeclaration by tasks.registering {
    group = "distribution"
    description = "Writes the digest-pinned Paper contribution bundle declaration for the auction experience."
    dependsOn(auctionExperienceBundleJar)
    inputs.file(auctionExperienceBundleJar.flatMap { it.archiveFile })
        .withPropertyName("auctionExperienceBundleJar")
    outputs.file(auctionExperiencePaperBundleDeclarationFile)

    doLast {
        val jarFile = auctionExperienceBundleJar.get().archiveFile.get().asFile
        val declarationFile = auctionExperiencePaperBundleDeclarationFile.get().asFile
        declarationFile.parentFile.mkdirs()
        declarationFile.writeText("""
            |artifact.id=artifact.auction-experience-bundle
            |artifact.digest=${sha256(jarFile)}
            |artifact.compatibility=fulcrum-bundle-v1
            |artifact.file=auction-experience-bundle.jar
            |descriptor.digest=auction-experience-descriptor-dev
            |contributions=Paper.Menus:network:0
            |object.bucket=artifact-store
            |""".trimMargin())
    }
}

val auctionExperiencePaperBundleContext by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles the local Paper contribution bundle context for auction experience validation."
    dependsOn(auctionExperienceBundleJar, auctionExperiencePaperBundleDeclaration)

    into(layout.buildDirectory.dir("paper-contribution-bundle"))
    from(auctionExperienceBundleJar) {
        rename { "auction-experience-bundle.jar" }
    }
    from(auctionExperiencePaperBundleDeclarationFile)
}

val defaultPaperGameserverImage = "ghcr.io/sh-harold/fulcrum-paper-gameserver:dev"
val paperGameserverImageTag = providers.gradleProperty("fulcrum.paperGameserverImage")
    .orElse(defaultPaperGameserverImage)
val basePaperGameserverImageContextDirectory =
    rootProject.layout.projectDirectory.dir("distribution/service-launcher/build/paper-gameserver-image")

val auctionExperiencePaperGameserverImageContext by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Assembles a validation Paper GameServer image context with the auction contribution bundle."
    dependsOn(":distribution:service-launcher:paperGameserverImageContext", auctionExperiencePaperBundleContext)

    into(layout.buildDirectory.dir("auction-experience-paper-gameserver-image"))
    from(basePaperGameserverImageContextDirectory)
    from(layout.buildDirectory.dir("paper-contribution-bundle")) {
        into("contribution-bundles")
    }
}

tasks.register<Exec>("auctionExperiencePaperGameserverImage") {
    group = "distribution"
    description = "Builds the validation Paper GameServer image with the auction contribution bundle overlay."
    dependsOn(auctionExperiencePaperGameserverImageContext)
    workingDir(layout.buildDirectory.dir("auction-experience-paper-gameserver-image"))
    doFirst {
        commandLine("docker", "build", "-t", paperGameserverImageTag.get(), ".")
    }
}

val auctionExperiencePaperGameserverImageReceiptFile =
    layout.buildDirectory.file("cluster-e2e/auction-experience-paper-gameserver-image-receipt.txt")

tasks.register("auctionExperiencePaperGameserverImageReceipt") {
    group = "verification"
    description = "Records the validation Paper GameServer overlay image used by generated-cluster escrow E2E."
    dependsOn(tasks.named("auctionExperiencePaperGameserverImage"), auctionExperiencePaperBundleDeclaration)
    outputs.file(auctionExperiencePaperGameserverImageReceiptFile)

    doLast {
        val imageId = providers.exec {
            commandLine("docker", "image", "inspect", "--format", "{{.Id}}", paperGameserverImageTag.get())
        }.standardOutput.asText.get().trim()
        val declaration = auctionExperiencePaperBundleDeclarationFile.get().asFile
        val artifactDigest = declaration.readLines()
            .firstOrNull { it.startsWith("artifact.digest=") }
            ?.substringAfter("=")
            ?: error("auction experience bundle declaration is missing artifact.digest")
        val receipt = auctionExperiencePaperGameserverImageReceiptFile.get().asFile
        receipt.parentFile.mkdirs()
        receipt.writeText("""
            |schema=auction-experience-paper-gameserver-image/v1
            |imageTag=${paperGameserverImageTag.get()}
            |imageId=$imageId
            |bundleDeclaration=${declaration.absolutePath}
            |bundleArtifactDigest=$artifactDigest
            |bundleDirectory=/opt/fulcrum/paper/contribution-bundles
            |""".trimMargin())
    }
}

tasks.named("check") {
    dependsOn(auctionExperiencePaperBundleContext)
    dependsOn(auctionExperiencePaperGameserverImageContext)
}
