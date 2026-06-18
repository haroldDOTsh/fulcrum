import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import java.io.File
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
val defaultObjectStoreImage = "quay.io/minio/minio:${libs.versions.minio.get()}"
val objectStoreImageTag = providers.gradleProperty("fulcrum.objectStoreImage")
    .orElse(defaultObjectStoreImage)
val defaultKafkaImage = "apache/kafka-native:${libs.versions.kafka.get()}"
val kafkaImageTag = providers.gradleProperty("fulcrum.kafkaImage")
    .orElse(defaultKafkaImage)
val defaultPostgresImage = "postgres:${libs.versions.postgresImage.get()}"
val postgresImageTag = providers.gradleProperty("fulcrum.postgresImage")
    .orElse(defaultPostgresImage)
val defaultCassandraImage = "cassandra:${libs.versions.cassandraImage.get()}"
val cassandraImageTag = providers.gradleProperty("fulcrum.cassandraImage")
    .orElse(defaultCassandraImage)
val defaultValkeyImage = "valkey/valkey:${libs.versions.valkeyImage.get()}"
val valkeyImageTag = providers.gradleProperty("fulcrum.valkeyImage")
    .orElse(defaultValkeyImage)
val agonesReleaseName = providers.gradleProperty("fulcrum.agonesReleaseName")
    .orElse("agones")
val agonesSystemNamespace = providers.gradleProperty("fulcrum.agonesSystemNamespace")
    .orElse("agones-system")
val kubeconfig = providers.gradleProperty("fulcrum.kubeconfig")
val generatedClusterKubeconfig = layout.buildDirectory.file("cluster-e2e/kubeconfig.yaml")
val clusterK3sImage = providers.gradleProperty("fulcrum.k3sImage")
    .orElse("rancher/k3s:${libs.versions.k3s.get()}")
val clusterK3sContainerName = providers.gradleProperty("fulcrum.k3sContainerName")
    .orElse("fulcrum-cluster-e2e")
val clusterK3sApiPort = providers.gradleProperty("fulcrum.k3sApiPort")
    .orElse("16443")
val clusterK3sMinecraftPort = providers.gradleProperty("fulcrum.k3sMinecraftPort")
    .orElse("25565")
val clusterK3sCgroupNamespace = providers.gradleProperty("fulcrum.k3sCgroupNamespace")
    .orElse("host")
val keepK3s = providers.gradleProperty("fulcrum.keepK3s")
    .orElse("false")
val clusterK3sImageArchive = layout.buildDirectory.file("cluster-e2e/fulcrum-images.tar")
val lobbyEndpointHost = providers.gradleProperty("fulcrum.lobbyEndpointHost")
val lobbyEndpointPort = providers.gradleProperty("fulcrum.lobbyEndpointPort")
    .orElse(clusterK3sMinecraftPort)
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
val verifyLobbyRouteAttemptState = providers.gradleProperty("fulcrum.verifyLobbyRouteAttemptState")
val lobbyRouteAttemptStateTopic = providers.gradleProperty("fulcrum.lobbyRouteAttemptStateTopic")
    .orElse("ctrl.state.route-attempt")
val verifyLobbyLoginRoutingCommandLog = providers.gradleProperty("fulcrum.verifyLobbyLoginRoutingCommandLog")
val lobbyQueueRosterCommandTopic = providers.gradleProperty("fulcrum.lobbyQueueRosterCommandTopic")
    .orElse("ctrl.cmd.queue-roster")
val verifyLobbyQueueRosterState = providers.gradleProperty("fulcrum.verifyLobbyQueueRosterState")
val lobbyQueueRosterStateTopic = providers.gradleProperty("fulcrum.lobbyQueueRosterStateTopic")
    .orElse("ctrl.state.queue-roster")
val lobbyPresenceAuthorityCommandTopic = providers.gradleProperty("fulcrum.lobbyPresenceAuthorityCommandTopic")
    .orElse("cmd.presence")
val lobbySharedShardPlacementCommandTopic = providers.gradleProperty("fulcrum.lobbySharedShardPlacementCommandTopic")
    .orElse("ctrl.cmd.shared-shard-placement")
val lobbyRouteAttemptCommandTopic = providers.gradleProperty("fulcrum.lobbyRouteAttemptCommandTopic")
    .orElse("ctrl.cmd.route-attempt")
val lobbyLifecycleTraceCommandTopic = providers.gradleProperty("fulcrum.lobbyLifecycleTraceCommandTopic")
    .orElse("ctrl.cmd.lifecycle-trace")
val verifyLobbyLifecycleTraceState = providers.gradleProperty("fulcrum.verifyLobbyLifecycleTraceState")
val lobbyLifecycleTraceStateTopic = providers.gradleProperty("fulcrum.lobbyLifecycleTraceStateTopic")
    .orElse("ctrl.state.lifecycle-trace")
val verifyLobbyRouteAuthorityCommandLog = providers.gradleProperty("fulcrum.verifyLobbyRouteAuthorityCommandLog")
val lobbyRouteAuthorityCommandTopic = providers.gradleProperty("fulcrum.lobbyRouteAuthorityCommandTopic")
    .orElse("cmd.route")
val verifyLobbyRouteAuthorityState = providers.gradleProperty("fulcrum.verifyLobbyRouteAuthorityState")
val lobbyRouteAuthorityStateTopic = providers.gradleProperty("fulcrum.lobbyRouteAuthorityStateTopic")
    .orElse("state.route")
val verifyLobbyHostRouteCommandLogs = providers.gradleProperty("fulcrum.verifyLobbyHostRouteCommandLogs")
val lobbyProxyRouteCommandTopic = providers.gradleProperty("fulcrum.lobbyProxyRouteCommandTopic")
    .orElse("host.velocity.routes")
val lobbyPaperHostCommandTopic = providers.gradleProperty("fulcrum.lobbyPaperHostCommandTopic")
    .orElse("host.paper.commands")
val verifyLobbyHostObservationLog = providers.gradleProperty("fulcrum.verifyLobbyHostObservationLog")
val lobbyHostObservationTopic = providers.gradleProperty("fulcrum.lobbyHostObservationTopic")
    .orElse("host.observation")
val verifyLobbyPresenceAuthorityState = providers.gradleProperty("fulcrum.verifyLobbyPresenceAuthorityState")
val lobbyPresenceAuthorityStateTopic = providers.gradleProperty("fulcrum.lobbyPresenceAuthorityStateTopic")
    .orElse("state.presence")
val verifyLobbyStandardCapabilityState = providers.gradleProperty("fulcrum.verifyLobbyStandardCapabilityState")
val lobbyPlayerProfileStateTopic = providers.gradleProperty("fulcrum.lobbyPlayerProfileStateTopic")
    .orElse("state.standard.player-profile")
val lobbyRankStateTopic = providers.gradleProperty("fulcrum.lobbyRankStateTopic")
    .orElse("state.standard.rank")
val lobbyPunishmentStateTopic = providers.gradleProperty("fulcrum.lobbyPunishmentStateTopic")
    .orElse("state.standard.punishment")
val verifyLobbyStandardCapabilityCommandLog =
    providers.gradleProperty("fulcrum.verifyLobbyStandardCapabilityCommandLog")
val lobbyPlayerProfileCommandTopic = providers.gradleProperty("fulcrum.lobbyPlayerProfileCommandTopic")
    .orElse("cmd.standard.player-profile")
val lobbyRankCommandTopic = providers.gradleProperty("fulcrum.lobbyRankCommandTopic")
    .orElse("cmd.standard.rank")
val lobbyPunishmentCommandTopic = providers.gradleProperty("fulcrum.lobbyPunishmentCommandTopic")
    .orElse("cmd.standard.punishment")
val verifyLobbyRewardState = providers.gradleProperty("fulcrum.verifyLobbyRewardState")
val lobbyEconomyStateTopic = providers.gradleProperty("fulcrum.lobbyEconomyStateTopic")
    .orElse("state.standard.economy")
val lobbyStatsStateTopic = providers.gradleProperty("fulcrum.lobbyStatsStateTopic")
    .orElse("state.standard.stats")
val verifyLobbyRewardCommandLog = providers.gradleProperty("fulcrum.verifyLobbyRewardCommandLog")
val lobbyEconomyCommandTopic = providers.gradleProperty("fulcrum.lobbyEconomyCommandTopic")
    .orElse("cmd.standard.economy")
val lobbyStatsCommandTopic = providers.gradleProperty("fulcrum.lobbyStatsCommandTopic")
    .orElse("cmd.standard.stats")
val expectedLobbyRewardCurrencyKey = providers.gradleProperty("fulcrum.expectedLobbyRewardCurrencyKey")
    .orElse("coins")
val expectedLobbyRewardAmountMinorUnits = providers.gradleProperty("fulcrum.expectedLobbyRewardAmountMinorUnits")
    .orElse("250")
val expectedLobbyRewardStatKey = providers.gradleProperty("fulcrum.expectedLobbyRewardStatKey")
    .orElse("session-completions")
val expectedLobbyRewardCommandDeliveryCopies =
    providers.gradleProperty("fulcrum.expectedLobbyRewardCommandDeliveryCopies")
        .orElse("2")
val verifyLobbyCassandraHotProjections = providers.gradleProperty("fulcrum.verifyLobbyCassandraHotProjections")
val lobbyCassandraPodName = providers.gradleProperty("fulcrum.lobbyCassandraPodName")
    .orElse("fulcrum-cassandra-0")
val lobbyCassandraContainerName = providers.gradleProperty("fulcrum.lobbyCassandraContainerName")
    .orElse("cassandra")
val lobbyCassandraCqlshPath = providers.gradleProperty("fulcrum.lobbyCassandraCqlshPath")
    .orElse("cqlsh")
val verifyLobbyPostgresAuthorityRecords = providers.gradleProperty("fulcrum.verifyLobbyPostgresAuthorityRecords")
val lobbyPostgresPodName = providers.gradleProperty("fulcrum.lobbyPostgresPodName")
    .orElse("fulcrum-postgres-0")
val lobbyPostgresContainerName = providers.gradleProperty("fulcrum.lobbyPostgresContainerName")
    .orElse("postgres")
val lobbyPostgresPsqlPath = providers.gradleProperty("fulcrum.lobbyPostgresPsqlPath")
    .orElse("psql")
val lobbyPostgresDatabase = providers.gradleProperty("fulcrum.lobbyPostgresDatabase")
    .orElse("fulcrum")
val lobbyPostgresUsername = providers.gradleProperty("fulcrum.lobbyPostgresUsername")
    .orElse("fulcrum")
val verifyLobbyValkeyCache = providers.gradleProperty("fulcrum.verifyLobbyValkeyCache")
val lobbyValkeyResourceName = providers.gradleProperty("fulcrum.lobbyValkeyResourceName")
    .orElse("deployment/fulcrum-valkey")
val lobbyValkeyContainerName = providers.gradleProperty("fulcrum.lobbyValkeyContainerName")
    .orElse("valkey")
val lobbyValkeyCliPath = providers.gradleProperty("fulcrum.lobbyValkeyCliPath")
    .orElse("valkey-cli")
val verifyLobbyProjectionConsistency = providers.gradleProperty("fulcrum.verifyLobbyProjectionConsistency")
val verifyLobbyTraceCorrelation = providers.gradleProperty("fulcrum.verifyLobbyTraceCorrelation")
val verifyLobbyObjectStoreArtifact = providers.gradleProperty("fulcrum.verifyLobbyObjectStoreArtifact")
val lobbyObjectStoreResourceName = providers.gradleProperty("fulcrum.lobbyObjectStoreResourceName")
    .orElse("service/fulcrum-object-store")
val lobbyObjectStorePort = providers.gradleProperty("fulcrum.lobbyObjectStorePort")
    .orElse("9000")
val lobbyObjectStoreRegion = providers.gradleProperty("fulcrum.lobbyObjectStoreRegion")
    .orElse("us-east-1")
val lobbyObjectStoreBucket = providers.gradleProperty("fulcrum.lobbyObjectStoreBucket")
    .orElse("artifact-store")
val lobbyObjectStoreSecretName = providers.gradleProperty("fulcrum.lobbyObjectStoreSecretName")
    .orElse("fulcrum-object-store-credentials")
val lobbyObjectStoreAccessKeySecretKey = providers.gradleProperty("fulcrum.lobbyObjectStoreAccessKeySecretKey")
    .orElse("FULCRUM_OBJECT_STORE_ACCESS_KEY")
val lobbyObjectStoreSecretKeySecretKey = providers.gradleProperty("fulcrum.lobbyObjectStoreSecretKeySecretKey")
    .orElse("FULCRUM_OBJECT_STORE_SECRET_KEY")
val expectedLobbyWorldArtifactId = providers.gradleProperty("fulcrum.expectedLobbyWorldArtifactId")
val expectedLobbyWorldArtifactDigest = providers.gradleProperty("fulcrum.expectedLobbyWorldArtifactDigest")
val expectedLobbyWorldArtifactCompatibility =
    providers.gradleProperty("fulcrum.expectedLobbyWorldArtifactCompatibility")
val verifyLobbySessionAuthorityState = providers.gradleProperty("fulcrum.verifyLobbySessionAuthorityState")
val lobbySessionAuthorityStateTopic = providers.gradleProperty("fulcrum.lobbySessionAuthorityStateTopic")
    .orElse("state.session")
val verifyLobbySessionAuthorityCommandLog = providers.gradleProperty("fulcrum.verifyLobbySessionAuthorityCommandLog")
val lobbySessionAuthorityCommandTopic = providers.gradleProperty("fulcrum.lobbySessionAuthorityCommandTopic")
    .orElse("cmd.session")
val verifyLobbySharedShardAllocationCommandLog =
    providers.gradleProperty("fulcrum.verifyLobbySharedShardAllocationCommandLog")
val lobbySharedShardAllocationCommandTopic =
    providers.gradleProperty("fulcrum.lobbySharedShardAllocationCommandTopic")
        .orElse("ctrl.cmd.shared-shard-allocation")
val verifyLobbySharedShardAllocationState =
    providers.gradleProperty("fulcrum.verifyLobbySharedShardAllocationState")
val lobbySharedShardAllocationStateTopic = providers.gradleProperty("fulcrum.lobbySharedShardAllocationStateTopic")
    .orElse("ctrl.state.shared-shard-allocation")
val lobbyKafkaPodName = providers.gradleProperty("fulcrum.lobbyKafkaPodName")
    .orElse("fulcrum-kafka-0")
val lobbyKafkaContainerName = providers.gradleProperty("fulcrum.lobbyKafkaContainerName")
    .orElse("kafka")
val lobbyKafkaBootstrapServer = providers.gradleProperty("fulcrum.lobbyKafkaBootstrapServer")
    .orElse("localhost:9092")
val lobbyKafkaConsoleConsumerPath = providers.gradleProperty("fulcrum.lobbyKafkaConsoleConsumerPath")
    .orElse("/opt/kafka/bin/kafka-console-consumer.sh")
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
val expectedLobbyExperienceId = providers.gradleProperty("fulcrum.expectedLobbyExperienceId")
    .orElse("experience-lobby")
val expectedLobbyPoolId = providers.gradleProperty("fulcrum.expectedLobbyPoolId")
    .orElse("pool-lobby")
val expectedLobbyResolvedManifestId = providers.gradleProperty("fulcrum.expectedLobbyResolvedManifestId")
    .orElse("manifest-lobby-bedrock-v1")
val expectedLobbyTraceId = providers.gradleProperty("fulcrum.expectedLobbyTraceId")
    .orElse("trace-paper-session-lobby-shared")
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
val lobbyEndpointReadyTimeout = providers.gradleProperty("fulcrum.lobbyEndpointReadyTimeout")
    .orElse("PT120S")
val lobbyRouteAttemptStateTimeout = providers.gradleProperty("fulcrum.lobbyRouteAttemptStateTimeout")
    .orElse("PT60S")
val lobbyRouteAttemptStateFreshnessSkew = providers.gradleProperty("fulcrum.lobbyRouteAttemptStateFreshnessSkew")
    .orElse("PT5S")
val lobbyPresenceAuthorityStateTimeout = providers.gradleProperty("fulcrum.lobbyPresenceAuthorityStateTimeout")
    .orElse("PT60S")
val lobbyPresenceAuthorityStateFreshnessSkew =
    providers.gradleProperty("fulcrum.lobbyPresenceAuthorityStateFreshnessSkew")
        .orElse("PT5S")
val lobbyStandardCapabilityStateTimeout = providers.gradleProperty("fulcrum.lobbyStandardCapabilityStateTimeout")
    .orElse("PT60S")
val lobbyCassandraHotProjectionTimeout = providers.gradleProperty("fulcrum.lobbyCassandraHotProjectionTimeout")
    .orElse("PT60S")
val lobbyPostgresAuthorityRecordTimeout = providers.gradleProperty("fulcrum.lobbyPostgresAuthorityRecordTimeout")
    .orElse("PT60S")
val lobbyValkeyCacheTimeout = providers.gradleProperty("fulcrum.lobbyValkeyCacheTimeout")
    .orElse("PT60S")
val lobbyObjectStoreArtifactTimeout = providers.gradleProperty("fulcrum.lobbyObjectStoreArtifactTimeout")
    .orElse("PT60S")
val lobbySessionAuthorityStateTimeout = providers.gradleProperty("fulcrum.lobbySessionAuthorityStateTimeout")
    .orElse("PT60S")
val lobbySessionAuthorityStateFreshnessSkew =
    providers.gradleProperty("fulcrum.lobbySessionAuthorityStateFreshnessSkew")
        .orElse("PT5S")
val lobbySharedShardAllocationStateTimeout =
    providers.gradleProperty("fulcrum.lobbySharedShardAllocationStateTimeout")
        .orElse("PT60S")

fun taskNameTargets(taskName: String, target: String): Boolean =
    taskName == target || taskName.endsWith(":$target")

val generatedClusterRequested = providers.provider {
    val taskNames = gradle.startParameter.taskNames
    val requestedGeneratedCluster = taskNames.any {
        taskNameTargets(it, "clusterE2e") || taskNameTargets(it, "clusterK3sE2e")
    }
    val requestedExistingCluster = taskNames.any { taskNameTargets(it, "clusterExistingE2e") }
    requestedGeneratedCluster && !requestedExistingCluster
}

fun effectiveKubeconfigPath(): String? {
    val explicitKubeconfig = kubeconfig.orNull?.takeIf { it.isNotBlank() }
    return explicitKubeconfig
        ?: if (generatedClusterRequested.get()) generatedClusterKubeconfig.get().asFile.absolutePath else null
}

fun kubectlCommand(vararg args: String): List<String> = buildList {
    add("kubectl")
    val explicitKubeconfig = effectiveKubeconfigPath()
    if (explicitKubeconfig != null) {
        add("--kubeconfig")
        add(explicitKubeconfig)
    } else {
        kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {
            add("--context")
            add(it)
        }
    }
    addAll(args)
}

fun helmCommand(vararg args: String): List<String> = buildList {
    add("helm")
    val explicitKubeconfig = effectiveKubeconfigPath()
    if (explicitKubeconfig != null) {
        add("--kubeconfig")
        add(explicitKubeconfig)
    } else {
        kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {
            add("--kube-context")
            add(it)
        }
    }
    addAll(args)
}

fun verifierKubeArgs(runArgs: MutableList<String>) {
    val explicitKubeconfig = effectiveKubeconfigPath()
    if (explicitKubeconfig != null) {
        runArgs.add("--kubeconfig=$explicitKubeconfig")
    } else {
        kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--kube-context=$it")
        }
    }
}

fun dockerCommand(vararg args: String): List<String> = buildList {
    add("docker")
    addAll(args)
}

data class ClusterProcessResult(
    val command: List<String>,
    val exitCode: Int,
    val output: String)

data class ClusterPreflightCheck(
    val label: String,
    val command: List<String>,
    val recoveryHint: String)

fun runClusterProcess(
    command: List<String>,
    timeoutSeconds: Long,
    workingDir: File = project.projectDir): ClusterProcessResult {
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()
    val finished = try {
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        process.destroyForcibly()
        throw GradleException("Interrupted while running `${command.joinToString(" ")}`.", exception)
    }
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (!finished) {
        process.destroyForcibly()
        return ClusterProcessResult(command, -1, output)
    }
    return ClusterProcessResult(command, process.exitValue(), output)
}

fun requireClusterProcess(
    command: List<String>,
    timeoutSeconds: Long,
    workingDir: File = project.projectDir): String {
    val result = runClusterProcess(command, timeoutSeconds, workingDir)
    if (result.exitCode != 0) {
        val formattedOutput = result.output.ifBlank { "<no output>" }.prependIndent("  ")
        throw GradleException(
            "Command `${result.command.joinToString(" ")}` failed with exit code ${result.exitCode}."
                + System.lineSeparator()
                + "Output:"
                + System.lineSeparator()
                + formattedOutput)
    }
    return result.output
}

fun dockerContainerExists(containerName: String): Boolean =
    runClusterProcess(
        dockerCommand("ps", "-aq", "--filter", "name=^/${containerName}$"),
        30)
        .output
        .isNotBlank()

fun dockerContainerRunning(containerName: String): Boolean =
    runClusterProcess(
        dockerCommand("inspect", "-f", "{{.State.Running}}", containerName),
        30)
        .let { result -> result.exitCode == 0 && result.output.equals("true", ignoreCase = true) }

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
            val formattedOutput = output.ifBlank { "<no output>" }.prependIndent("  ")
            """
            |${check.label} failed while running `$displayCommand` with exit code ${process.exitValue()}.
            |${check.recoveryHint}
            |Output:
            |$formattedOutput
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
        val message = failures.joinToString(System.lineSeparator() + System.lineSeparator())
        throw GradleException(
            "Cluster preflight failed:${System.lineSeparator()}${System.lineSeparator()}$message")
    }
}

val requiredAgonesCrds = listOf(
    "gameservers.agones.dev",
    "fleets.agones.dev",
    "fleetautoscalers.autoscaling.agones.dev",
    "gameserversets.agones.dev")

val requiredAgonesApiResources = mapOf(
    "allocation.agones.dev" to "gameserverallocations.allocation.agones.dev")

fun waitForAgonesCrds(label: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300)
    val failures = mutableListOf<String>()
    while (System.nanoTime() < deadline) {
        val crdResult = runClusterProcess(kubectlCommand("get", "crd", *requiredAgonesCrds.toTypedArray()), 30)
        val apiFailures = requiredAgonesApiResources.mapNotNull { (group, resourceName) ->
            val apiResult = runClusterProcess(kubectlCommand("api-resources", "--api-group=$group", "-o", "name"), 30)
            if (apiResult.exitCode == 0 && apiResult.output.lineSequence().any { it.trim() == resourceName }) {
                null
            } else {
                apiResult.output.ifBlank { "kubectl api-resources did not list $resourceName" }
            }
        }
        if (crdResult.exitCode == 0 && apiFailures.isEmpty()) {
            return
        }
        failures.add(
            (listOf(crdResult.output.ifBlank { "kubectl get crd returned exit code ${crdResult.exitCode}" })
                + apiFailures)
                .joinToString(" ; "))
        Thread.sleep(5_000)
    }
    throw GradleException(
        "Timed out waiting for Agones APIs after $label. Last failures: "
            + failures.takeLast(5).joinToString(" | "))
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
val renderedAgonesHelmChart = layout.buildDirectory.file("kubernetes/paper-agones/agones-helm-chart.yaml")

tasks.register("clusterK3sPreflight") {
    group = "verification"
    description = "Verifies Docker can run the Gradle-owned K3s cluster used by clusterE2e."
    doLast {
        if (clusterK3sImage.get().isBlank()) {
            throw GradleException("K3s image reference is empty; set -Pfulcrum.k3sImage=<image-ref>.")
        }
        runClusterPreflightChecks(listOf(
            ClusterPreflightCheck(
                "Docker daemon check",
                dockerCommand("version", "--format", "{{.Client.Version}} {{.Server.Version}}"),
                "Start Docker Desktop and ensure this user can access the Linux engine before running clusterE2e.")))
    }
}

tasks.register("clusterK3sStart") {
    group = "deployment"
    description = "Starts the Gradle-owned K3s container and writes its generated kubeconfig under build/cluster-e2e."
    dependsOn(tasks.named("clusterK3sPreflight"))
    doLast {
        val containerName = clusterK3sContainerName.get()
        fun runK3sContainer() {
            val runArgs = mutableListOf(
                "run",
                "-d",
                "--name",
                containerName,
                "--privileged")
            clusterK3sCgroupNamespace.get()
                .takeIf { it.isNotBlank() }
                ?.let { namespace ->
                    runArgs.add("--cgroupns")
                    runArgs.add(namespace)
                }
            runArgs.addAll(listOf(
                "-p",
                "127.0.0.1:${clusterK3sApiPort.get()}:6443",
                "-p",
                "127.0.0.1:${clusterK3sMinecraftPort.get()}:25565",
                clusterK3sImage.get(),
                "server",
                "--disable=traefik",
                "--tls-san=127.0.0.1",
                "--write-kubeconfig-mode=644"))
            requireClusterProcess(dockerCommand(*runArgs.toTypedArray()), 180)
        }

        if (!dockerContainerExists(containerName)) {
            runK3sContainer()
        } else if (!dockerContainerRunning(containerName)) {
            logger.lifecycle("Removing stopped K3s container $containerName before recreating it")
            requireClusterProcess(dockerCommand("rm", "-f", containerName), 60)
            runK3sContainer()
        } else {
            logger.lifecycle("Reusing running K3s container $containerName")
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        val failures = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            if (!dockerContainerRunning(containerName)) {
                val logs = runClusterProcess(
                    dockerCommand("logs", "--tail", "120", containerName),
                    30)
                    .output
                    .ifBlank { "<no K3s logs>" }
                    .prependIndent("  ")
                throw GradleException(
                    "K3s container $containerName exited before node readiness."
                        + System.lineSeparator()
                        + "Logs:"
                        + System.lineSeparator()
                        + logs)
            }
            val result = runClusterProcess(
                dockerCommand(
                    "exec",
                    containerName,
                    "kubectl",
                    "wait",
                    "--for=condition=Ready",
                    "node",
                    "--all",
                    "--timeout=10s"),
                20)
            if (result.exitCode == 0) {
                val rawKubeconfig = requireClusterProcess(
                    dockerCommand("exec", containerName, "cat", "/etc/rancher/k3s/k3s.yaml"),
                    30)
                val hostApi = "https://127.0.0.1:${clusterK3sApiPort.get()}"
                val renderedKubeconfig = rawKubeconfig
                    .replace("https://127.0.0.1:6443", hostApi)
                    .replace("https://localhost:6443", hostApi)
                val kubeconfigFile = generatedClusterKubeconfig.get().asFile
                kubeconfigFile.parentFile.mkdirs()
                kubeconfigFile.writeText(renderedKubeconfig)
                logger.lifecycle("Wrote generated K3s kubeconfig to ${kubeconfigFile.absolutePath}")
                return@doLast
            }
            failures.add(result.output.ifBlank { "K3s node readiness returned exit code ${result.exitCode}" })
            Thread.sleep(3_000)
        }
        throw GradleException(
            "Timed out waiting for K3s node readiness in container $containerName. Last failures: "
                + failures.takeLast(5).joinToString(" | "))
    }
}

tasks.register("clusterK3sImportImages") {
    group = "deployment"
    description = "Imports the locally built Fulcrum service, Paper, and Velocity images into the K3s container runtime."
    dependsOn(
        tasks.named("clusterK3sStart"),
        tasks.named("serviceLauncherImage"),
        tasks.named("paperGameserverImage"),
        tasks.named("velocityProxyImage"))
    doLast {
        val archive = clusterK3sImageArchive.get().asFile
        archive.parentFile.mkdirs()
        requireClusterProcess(
            dockerCommand(
                "save",
                "-o",
                archive.absolutePath,
                serviceLauncherImageTag.get(),
                paperGameserverImageTag.get(),
                velocityProxyImageTag.get()),
            300)
        val archivePath = project.relativePath(archive).replace(File.separatorChar, '/')
        val containerName = clusterK3sContainerName.get()
        requireClusterProcess(
            dockerCommand("cp", archivePath, "$containerName:/tmp/fulcrum-cluster-e2e-images.tar"),
            120)
        requireClusterProcess(
            dockerCommand(
                "exec",
                containerName,
                "ctr",
                "-n",
                "k8s.io",
                "images",
                "import",
                "/tmp/fulcrum-cluster-e2e-images.tar"),
            300)
        logger.lifecycle("Imported Fulcrum images into K3s container $containerName")
    }
}

tasks.register("clusterK3sStop") {
    group = "deployment"
    description = "Removes the Gradle-owned K3s container unless -Pfulcrum.keepK3s=true is set."
    doLast {
        val containerName = clusterK3sContainerName.get()
        if (keepK3s.get().equals("true", ignoreCase = true)) {
            logger.lifecycle("Keeping K3s container $containerName because -Pfulcrum.keepK3s=true")
        } else if (dockerContainerExists(containerName)) {
            requireClusterProcess(dockerCommand("rm", "-f", containerName), 60)
        } else {
            logger.lifecycle("No K3s container named $containerName exists")
        }
    }
}

tasks.register("paperAgonesClusterPreflight") {
    group = "verification"
    description = "Verifies kubectl, Docker, and Helm when needed before deploying Paper Agones resources."
    doLast {
        val checks = mutableListOf(
            ClusterPreflightCheck(
                "Kubernetes context check",
                kubectlCommand("config", "current-context"),
                "Enable Docker Desktop Kubernetes, pass -Pfulcrum.kubeContext=<context>, or pass -Pfulcrum.kubeconfig=<path> for a generated cluster."),
            ClusterPreflightCheck(
                "Kubernetes API reachability check",
                kubectlCommand("version", "--output=yaml"),
                "Verify kubectl can reach the target cluster before running clusterE2e."))
        if (generatedClusterRequested.get()) {
            logger.lifecycle("Skipping host Helm availability check; K3s clusterE2e uses the in-cluster HelmChart controller for Agones.")
        } else {
            checks.add(ClusterPreflightCheck(
                "Helm availability check",
                helmCommand("version", "--short"),
                "Install Helm and ensure `helm` is on PATH before Agones installation."))
        }
        checks.add(
            ClusterPreflightCheck(
                "Docker Desktop daemon check",
                dockerCommand("version", "--format", "{{.Client.Version}} {{.Server.Version}}"),
                "Start Docker Desktop and ensure this user can access the Linux engine before building clusterE2e images."))
        runClusterPreflightChecks(checks)
    }
}

tasks.register<Exec>("paperAgonesApplyNamespace") {
    group = "deployment"
    description = "Applies the Fulcrum lobby GameServer namespace before installing Agones."
    doFirst {
        commandLine(kubectlCommand("apply", "-f", lobbyNamespaceManifest.asFile.absolutePath))
    }
}

tasks.register("paperAgonesInstallAgones") {
    group = "deployment"
    description = "Installs or upgrades Agones for the Fulcrum lobby GameServer namespace."
    dependsOn(tasks.named("paperAgonesApplyNamespace"))
    doLast {
        if (generatedClusterRequested.get()) {
            val valuesContent = agonesHelmValues.asFile.readText().prependIndent("    ")
            val helmChart = renderedAgonesHelmChart.get().asFile
            helmChart.parentFile.mkdirs()
            helmChart.writeText("""
                |apiVersion: helm.cattle.io/v1
                |kind: HelmChart
                |metadata:
                |  name: ${agonesReleaseName.get()}
                |  namespace: kube-system
                |spec:
                |  repo: https://agones.dev/chart/stable
                |  chart: agones
                |  version: ${agonesChartVersion.get()}
                |  targetNamespace: ${agonesSystemNamespace.get()}
                |  createNamespace: true
                |  valuesContent: |-
                |$valuesContent
                |""".trimMargin())
            providers.exec {
                commandLine(kubectlCommand("apply", "-f", helmChart.absolutePath))
            }.result.get().assertNormalExitValue()
            waitForAgonesCrds("K3s HelmChart Agones install")
        } else {
            providers.exec {
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
            }.result.get().assertNormalExitValue()
        }
    }
}

tasks.register("paperAgonesConfigureAllocatorTls") {
    group = "deployment"
    description = "Configures the Agones allocator for CA-verified HTTPS used by controller-service."
    dependsOn(tasks.named("paperAgonesInstallAgones"))
    doLast {
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                agonesSystemNamespace.get(),
                "set",
                "env",
                "deployment/agones-allocator",
                "DISABLE_MTLS=true"))
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                agonesSystemNamespace.get(),
                "rollout",
                "status",
                "deployment/agones-allocator",
                "--timeout=180s"))
        }.result.get().assertNormalExitValue()
    }
}

tasks.register<Sync>("paperAgonesRenderManifests") {
    group = "distribution"
    description = "Renders Paper Agones Kubernetes manifests with the effective container image tags."
    inputs.property("serviceLauncherImageTag", serviceLauncherImageTag)
    inputs.property("paperGameserverImageTag", paperGameserverImageTag)
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
    inputs.property("serviceLauncherImageTag", serviceLauncherImageTag)
    inputs.property("kafkaImageTag", kafkaImageTag)
    inputs.property("postgresImageTag", postgresImageTag)
    inputs.property("cassandraImageTag", cassandraImageTag)
    inputs.property("valkeyImageTag", valkeyImageTag)
    inputs.property("objectStoreImageTag", objectStoreImageTag)
    into(layout.buildDirectory.dir("kubernetes/substrate"))
    from(lobbyKafkaManifest) {
        filter { line: String ->
            line
                .replace(defaultServiceLauncherImage, serviceLauncherImageTag.get())
                .replace(defaultKafkaImage, kafkaImageTag.get())
                .replace(defaultPostgresImage, postgresImageTag.get())
                .replace(defaultCassandraImage, cassandraImageTag.get())
                .replace(defaultValkeyImage, valkeyImageTag.get())
                .replace(defaultObjectStoreImage, objectStoreImageTag.get())
        }
    }
}

tasks.register("paperAgonesSyncAllocatorCa") {
    group = "deployment"
    description = "Copies the Agones allocator TLS CA into the Fulcrum lobby namespace for controller-service HTTPS."
    dependsOn(
        tasks.named("paperAgonesApplyNamespace"),
        tasks.named("paperAgonesConfigureAllocatorTls"))
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
    inputs.property("velocityProxyImageTag", velocityProxyImageTag)
    inputs.property("lobbyTargetCapacity", lobbyTargetCapacity)
    inputs.property("lobbyHardCapacity", lobbyHardCapacity)
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

tasks.register<Exec>("paperAgonesWaitForObjectStorage") {
    group = "verification"
    description = "Waits for the in-cluster S3-compatible object storage dependency used by artifact provisioning and Paper."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "statefulset/fulcrum-object-store",
            "--timeout=240s"))
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
    description = "Waits for the authority schema provisioner Job to apply record-store and hot-projection migration resources."
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

tasks.register<Exec>("paperAgonesWaitForWorkerAgent") {
    group = "verification"
    description = "Waits for the background worker-agent Deployment to become available."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "deployment/fulcrum-worker-agent",
            "--timeout=240s"))
    }
}

tasks.register("paperAgonesVerifyAgonesInstall") {
    group = "verification"
    description = "Verifies the Agones CRDs required by the lobby Paper deployment exist."
    doLast {
        waitForAgonesCrds("Agones install verification")
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

tasks.register("paperAgonesDeleteSharedShardAllocationJobs") {
    group = "deployment"
    description = "Deletes rerunnable shared-shard allocation Jobs before reapplying them."
    doLast {
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                "fulcrum-lobby",
                "delete",
                "job",
                "fulcrum-lobby-shared-shard-allocation",
                "fulcrum-lobby-shared-shard-allocation-materialization",
                "--ignore-not-found=true"))
        }.result.get().assertNormalExitValue()
    }
}

tasks.register<Exec>("paperAgonesApplySharedShardAllocation") {
    group = "deployment"
    description = "Applies the typed lobby shared-shard allocation provisioning Jobs after a Paper Fleet replica is Ready."
    dependsOn(
        tasks.named("serviceLauncherImage"),
        tasks.named("paperAgonesDeleteSharedShardAllocationJobs"),
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

tasks.register("paperAgonesRestartControllerServiceForReplay") {
    group = "verification"
    description = "Restarts controller-service after shared-shard allocation state materializes so the cluster gate verifies replay before login routing."
    doLast {
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                "fulcrum-lobby",
                "rollout",
                "restart",
                "deployment/fulcrum-controller-service"))
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(kubectlCommand(
                "-n",
                "fulcrum-lobby",
                "rollout",
                "status",
                "deployment/fulcrum-controller-service",
                "--timeout=240s"))
        }.result.get().assertNormalExitValue()
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

tasks.register<Exec>("velocityL4WaitForReady") {
    group = "verification"
    description = "Waits for the Fulcrum Velocity proxy Deployment to become available behind the L4 Service."
    doFirst {
        commandLine(kubectlCommand(
            "-n",
            "fulcrum-lobby",
            "rollout",
            "status",
            "deployment/fulcrum-velocity",
            "--timeout=240s"))
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
        tasks.named("paperAgonesConfigureAllocatorTls"),
        tasks.named("paperAgonesSyncAllocatorCa"),
        tasks.named("paperAgonesApplySubstrate"),
        tasks.named("paperAgonesWaitForKafka"),
        tasks.named("paperAgonesWaitForValkey"),
        tasks.named("paperAgonesWaitForObjectStorage"),
        tasks.named("paperAgonesWaitForPostgres"),
        tasks.named("paperAgonesWaitForCassandra"),
        tasks.named("paperAgonesWaitForAuthoritySchema"),
        tasks.named("paperAgonesWaitForAuthorityService"),
        tasks.named("paperAgonesWaitForControllerService"),
        tasks.named("paperAgonesWaitForWorkerAgent"),
        tasks.named("paperAgonesVerifyAgonesInstall"),
        tasks.named("paperAgonesApply"),
        tasks.named("paperAgonesWaitForWorldArtifact"),
        tasks.named("paperAgonesWaitForCapabilitySeed"),
        tasks.named("paperAgonesWaitForCapabilityMaterialization"),
        tasks.named("paperAgonesWaitForFleetReady"),
        tasks.named("paperAgonesApplySharedShardAllocation"),
        tasks.named("paperAgonesWaitForSharedShardAllocation"),
        tasks.named("paperAgonesWaitForSharedShardAllocationState"),
        tasks.named("paperAgonesRestartControllerServiceForReplay"),
        tasks.named("paperAgonesStatus"))
}

tasks.register("paperAgonesPhase3Deploy") {
    group = "deployment"
    description = "Builds images, deploys the lobby Paper Agones slice, applies Velocity L4 ingress resources, and prints cluster status."
    dependsOn(
        tasks.named("paperAgonesPhase2Deploy"),
        tasks.named("velocityL4Apply"),
        tasks.named("velocityL4WaitForReady"),
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
            "--route-attempt-state-topic=${lobbyRouteAttemptStateTopic.get()}",
            "--queue-roster-command-topic=${lobbyQueueRosterCommandTopic.get()}",
            "--queue-roster-state-topic=${lobbyQueueRosterStateTopic.get()}",
            "--presence-authority-command-topic=${lobbyPresenceAuthorityCommandTopic.get()}",
            "--shared-shard-placement-command-topic=${lobbySharedShardPlacementCommandTopic.get()}",
            "--route-attempt-command-topic=${lobbyRouteAttemptCommandTopic.get()}",
            "--lifecycle-trace-command-topic=${lobbyLifecycleTraceCommandTopic.get()}",
            "--lifecycle-trace-state-topic=${lobbyLifecycleTraceStateTopic.get()}",
            "--route-authority-command-topic=${lobbyRouteAuthorityCommandTopic.get()}",
            "--route-authority-state-topic=${lobbyRouteAuthorityStateTopic.get()}",
            "--proxy-route-command-topic=${lobbyProxyRouteCommandTopic.get()}",
            "--paper-host-command-topic=${lobbyPaperHostCommandTopic.get()}",
            "--host-observation-topic=${lobbyHostObservationTopic.get()}",
            "--presence-authority-state-topic=${lobbyPresenceAuthorityStateTopic.get()}",
            "--player-profile-state-topic=${lobbyPlayerProfileStateTopic.get()}",
            "--rank-state-topic=${lobbyRankStateTopic.get()}",
            "--punishment-state-topic=${lobbyPunishmentStateTopic.get()}",
            "--player-profile-command-topic=${lobbyPlayerProfileCommandTopic.get()}",
            "--rank-command-topic=${lobbyRankCommandTopic.get()}",
            "--punishment-command-topic=${lobbyPunishmentCommandTopic.get()}",
            "--economy-state-topic=${lobbyEconomyStateTopic.get()}",
            "--stats-state-topic=${lobbyStatsStateTopic.get()}",
            "--economy-command-topic=${lobbyEconomyCommandTopic.get()}",
            "--stats-command-topic=${lobbyStatsCommandTopic.get()}",
            "--expected-reward-currency-key=${expectedLobbyRewardCurrencyKey.get()}",
            "--expected-reward-amount-minor-units=${expectedLobbyRewardAmountMinorUnits.get()}",
            "--expected-reward-stat-key=${expectedLobbyRewardStatKey.get()}",
            "--expected-reward-command-delivery-copies=${expectedLobbyRewardCommandDeliveryCopies.get()}",
            "--cassandra-pod-name=${lobbyCassandraPodName.get()}",
            "--cassandra-container-name=${lobbyCassandraContainerName.get()}",
            "--cassandra-cqlsh-path=${lobbyCassandraCqlshPath.get()}",
            "--postgres-pod-name=${lobbyPostgresPodName.get()}",
            "--postgres-container-name=${lobbyPostgresContainerName.get()}",
            "--postgres-psql-path=${lobbyPostgresPsqlPath.get()}",
            "--postgres-database=${lobbyPostgresDatabase.get()}",
            "--postgres-username=${lobbyPostgresUsername.get()}",
            "--valkey-resource-name=${lobbyValkeyResourceName.get()}",
            "--valkey-container-name=${lobbyValkeyContainerName.get()}",
            "--valkey-cli-path=${lobbyValkeyCliPath.get()}",
            "--object-store-resource-name=${lobbyObjectStoreResourceName.get()}",
            "--object-store-port=${lobbyObjectStorePort.get()}",
            "--object-store-region=${lobbyObjectStoreRegion.get()}",
            "--object-store-bucket=${lobbyObjectStoreBucket.get()}",
            "--object-store-secret-name=${lobbyObjectStoreSecretName.get()}",
            "--object-store-access-key-secret-key=${lobbyObjectStoreAccessKeySecretKey.get()}",
            "--object-store-secret-key-secret-key=${lobbyObjectStoreSecretKeySecretKey.get()}",
            "--session-authority-state-topic=${lobbySessionAuthorityStateTopic.get()}",
            "--session-authority-command-topic=${lobbySessionAuthorityCommandTopic.get()}",
            "--shared-shard-allocation-command-topic=${lobbySharedShardAllocationCommandTopic.get()}",
            "--shared-shard-allocation-state-topic=${lobbySharedShardAllocationStateTopic.get()}",
            "--kafka-pod-name=${lobbyKafkaPodName.get()}",
            "--kafka-container-name=${lobbyKafkaContainerName.get()}",
            "--kafka-bootstrap-server=${lobbyKafkaBootstrapServer.get()}",
            "--kafka-console-consumer-path=${lobbyKafkaConsoleConsumerPath.get()}",
            "--protocol-version=${lobbyMinecraftProtocolVersion.get()}",
            "--login-username=${lobbyLoginUsername.get()}",
            "--second-login-username=${secondLobbyLoginUsername.get()}",
            "--expected-lobby-spawn-block=${expectedLobbySpawnBlock.get()}",
            "--expected-lobby-spawn-world=${expectedLobbySpawnWorld.get()}",
            "--expected-lobby-experience-id=${expectedLobbyExperienceId.get()}",
            "--expected-lobby-pool-id=${expectedLobbyPoolId.get()}",
            "--expected-lobby-resolved-manifest-id=${expectedLobbyResolvedManifestId.get()}",
            "--expected-lobby-trace-id=${expectedLobbyTraceId.get()}",
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
            "--endpoint-ready-timeout=${lobbyEndpointReadyTimeout.get()}",
            "--route-attempt-state-timeout=${lobbyRouteAttemptStateTimeout.get()}",
            "--route-attempt-state-freshness-skew=${lobbyRouteAttemptStateFreshnessSkew.get()}",
            "--presence-authority-state-timeout=${lobbyPresenceAuthorityStateTimeout.get()}",
            "--presence-authority-state-freshness-skew=${lobbyPresenceAuthorityStateFreshnessSkew.get()}",
            "--standard-capability-state-timeout=${lobbyStandardCapabilityStateTimeout.get()}",
            "--cassandra-hot-projection-timeout=${lobbyCassandraHotProjectionTimeout.get()}",
            "--postgres-authority-record-timeout=${lobbyPostgresAuthorityRecordTimeout.get()}",
            "--valkey-cache-timeout=${lobbyValkeyCacheTimeout.get()}",
            "--object-store-artifact-timeout=${lobbyObjectStoreArtifactTimeout.get()}",
            "--session-authority-state-timeout=${lobbySessionAuthorityStateTimeout.get()}",
            "--session-authority-state-freshness-skew=${lobbySessionAuthorityStateFreshnessSkew.get()}",
            "--shared-shard-allocation-state-timeout=${lobbySharedShardAllocationStateTimeout.get()}",
            "--timeout=${lobbyVerifierTimeout.get()}")
        lobbyEndpointHost.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--endpoint-host=$it")
        }
        verifierKubeArgs(runArgs)
        verifyLobbyAgonesFleetState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-agones-fleet-state=$it")
        }
        verifyLobbyRouteAttemptState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-route-attempt-state=$it")
        }
        verifyLobbyLoginRoutingCommandLog.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-login-routing-command-log=$it")
        }
        verifyLobbyQueueRosterState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-queue-roster-state=$it")
        }
        verifyLobbyLifecycleTraceState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-lifecycle-trace-state=$it")
        }
        verifyLobbyRouteAuthorityCommandLog.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-route-authority-command-log=$it")
        }
        verifyLobbyRouteAuthorityState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-route-authority-state=$it")
        }
        verifyLobbyHostRouteCommandLogs.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-host-route-command-logs=$it")
        }
        verifyLobbyHostObservationLog.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-host-observation-log=$it")
        }
        verifyLobbyPresenceAuthorityState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-presence-authority-state=$it")
        }
        verifyLobbyStandardCapabilityState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-standard-capability-state=$it")
        }
        verifyLobbyStandardCapabilityCommandLog.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-standard-capability-command-log=$it")
        }
        verifyLobbyRewardState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-reward-state=$it")
        }
        verifyLobbyRewardCommandLog.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-reward-command-log=$it")
        }
        verifyLobbyCassandraHotProjections.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-cassandra-hot-projections=$it")
        }
        verifyLobbyPostgresAuthorityRecords.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-postgres-authority-records=$it")
        }
        verifyLobbyValkeyCache.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-valkey-cache=$it")
        }
        verifyLobbyProjectionConsistency.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-projection-consistency=$it")
        }
        verifyLobbyTraceCorrelation.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-trace-correlation=$it")
        }
        verifyLobbyObjectStoreArtifact.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-object-store-artifact=$it")
        }
        expectedLobbyWorldArtifactId.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--expected-lobby-world-artifact-id=$it")
        }
        expectedLobbyWorldArtifactDigest.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--expected-lobby-world-artifact-digest=$it")
        }
        expectedLobbyWorldArtifactCompatibility.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--expected-lobby-world-artifact-compatibility=$it")
        }
        verifyLobbySessionAuthorityState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-session-authority-state=$it")
        }
        verifyLobbySessionAuthorityCommandLog.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-session-authority-command-log=$it")
        }
        verifyLobbySharedShardAllocationCommandLog.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-shared-shard-allocation-command-log=$it")
        }
        verifyLobbySharedShardAllocationState.orNull?.takeIf { it.isNotBlank() }?.let {
            runArgs.add("--verify-shared-shard-allocation-state=$it")
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

tasks.register("clusterK3sE2e") {
    group = "verification"
    description = "Runs the K3s-backed cluster E2E gate and tears down the Gradle-owned cluster by default."
    dependsOn(
        tasks.named("clusterK3sImportImages"),
        tasks.named("lobbyClusterE2eVerify"))
    finalizedBy(tasks.named("clusterK3sStop"))
}

tasks.named("paperAgonesApply") {
    mustRunAfter(tasks.named("clusterK3sImportImages"))
    mustRunAfter(tasks.named("paperAgonesVerifyAgonesInstall"))
    mustRunAfter(tasks.named("paperAgonesWaitForKafka"))
    mustRunAfter(tasks.named("paperAgonesWaitForValkey"))
    mustRunAfter(tasks.named("paperAgonesWaitForObjectStorage"))
    mustRunAfter(tasks.named("paperAgonesWaitForAuthorityService"))
    mustRunAfter(tasks.named("paperAgonesWaitForControllerService"))
    mustRunAfter(tasks.named("paperAgonesWaitForWorkerAgent"))
}

tasks.named("paperAgonesStatus") {
    mustRunAfter(tasks.named("paperAgonesRestartControllerServiceForReplay"))
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
    mustRunAfter(tasks.named("clusterK3sImportImages"))
    mustRunAfter(tasks.named("paperAgonesWaitForFleetReady"))
}

tasks.named("paperAgonesWaitForSharedShardAllocation") {
    mustRunAfter(tasks.named("paperAgonesApplySharedShardAllocation"))
}

tasks.named("paperAgonesWaitForSharedShardAllocationState") {
    mustRunAfter(tasks.named("paperAgonesWaitForSharedShardAllocation"))
}

tasks.named("paperAgonesRestartControllerServiceForReplay") {
    mustRunAfter(tasks.named("paperAgonesWaitForSharedShardAllocationState"))
}

tasks.named("paperAgonesAllocateLobby") {
    mustRunAfter(tasks.named("paperAgonesWaitForFleetReady"))
}

tasks.named("paperAgonesApplyNamespace") {
    mustRunAfter(tasks.named("paperAgonesClusterPreflight"))
}

tasks.named("paperAgonesClusterPreflight") {
    mustRunAfter(tasks.named("clusterK3sStart"))
}

tasks.named("paperAgonesInstallAgones") {
    mustRunAfter(tasks.named("paperAgonesApplyNamespace"))
}

tasks.named("paperAgonesConfigureAllocatorTls") {
    mustRunAfter(tasks.named("paperAgonesInstallAgones"))
}

tasks.named("paperAgonesVerifyAgonesInstall") {
    mustRunAfter(tasks.named("paperAgonesConfigureAllocatorTls"))
}

tasks.named("paperAgonesApplySubstrate") {
    mustRunAfter(tasks.named("clusterK3sImportImages"))
    mustRunAfter(tasks.named("paperAgonesApplyNamespace"))
    mustRunAfter(tasks.named("paperAgonesSyncAllocatorCa"))
}

tasks.named("paperAgonesWaitForKafka") {
    mustRunAfter(tasks.named("paperAgonesApplySubstrate"))
}

tasks.named("paperAgonesWaitForValkey") {
    mustRunAfter(tasks.named("paperAgonesApplySubstrate"))
}

tasks.named("paperAgonesWaitForObjectStorage") {
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

tasks.named("paperAgonesWaitForWorkerAgent") {
    mustRunAfter(tasks.named("paperAgonesWaitForKafka"))
    mustRunAfter(tasks.named("paperAgonesWaitForObjectStorage"))
}

tasks.named("paperAgonesWaitForCapabilityMaterialization") {
    mustRunAfter(tasks.named("paperAgonesWaitForCapabilitySeed"))
}

tasks.named("velocityL4Apply") {
    mustRunAfter(tasks.named("clusterK3sImportImages"))
    mustRunAfter(tasks.named("paperAgonesPhase2Deploy"))
}

tasks.named("velocityL4Status") {
    mustRunAfter(tasks.named("velocityL4WaitForReady"))
}

tasks.named("velocityL4WaitForReady") {
    mustRunAfter(tasks.named("velocityL4Apply"))
}

tasks.named("lobbyClusterE2eVerify") {
    mustRunAfter(rootProject.tasks.named("step8Check"))
    mustRunAfter(tasks.named("paperAgonesPhase3Deploy"))
}

tasks.named("clusterK3sStart") {
    mustRunAfter(rootProject.tasks.named("step8Check"))
}

tasks.named("check") {
    dependsOn(paperGameserverImageContext)
    dependsOn(velocityProxyImageContext)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    reports.html.required.set(false)
}
