package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgonesDeploymentManifestTest {
    @Test
    void lobbyPaperFleetDeclaresAgonesSdkAndAutoscalerShape() throws IOException {
        String manifest = resource("fulcrum/kubernetes/agones/lobby-paper-fleet.yaml");

        assertTrue(manifest.contains("apiVersion: agones.dev/v1"));
        assertTrue(manifest.contains("kind: Fleet"));
        assertTrue(manifest.contains("kind: Job"));
        assertTrue(manifest.contains("name: fulcrum-lobby-world-artifact"));
        assertTrue(manifest.contains("serviceAccountName: fulcrum-content-provisioner"));
        assertTrue(manifest.contains("image: ghcr.io/sh-harold/fulcrum-service-launcher:dev"));
        assertTrue(manifest.contains("sh.harold.fulcrum.distribution.launcher.LobbyWorldArtifactProvisioner"));
        assertTrue(manifest.contains("name: FULCRUM_KAFKA_BOOTSTRAP_SERVERS"));
        assertTrue(manifest.contains("value: \"cmd.artifact-metadata\""));
        assertTrue(manifest.contains("value: \"principal-lobby-world-provisioner\""));
        assertTrue(manifest.contains("name: fulcrum-capability-provisioner"));
        assertTrue(manifest.contains("name: fulcrum-lobby-capability-seed"));
        assertTrue(manifest.contains("serviceAccountName: fulcrum-capability-provisioner"));
        assertTrue(manifest.contains("sh.harold.fulcrum.distribution.launcher.LobbyCapabilitySeedProvisioner"));
        assertTrue(manifest.contains("name: fulcrum-lobby-capability-materialization"));
        assertTrue(manifest.contains("sh.harold.fulcrum.distribution.launcher.LobbyCapabilityMaterializationVerifier"));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_CAPABILITY_MATERIALIZATION_TIMEOUT"));
        assertTrue(manifest.contains("value: \"cmd.standard.player-profile\""));
        assertTrue(manifest.contains("value: \"cmd.standard.rank\""));
        assertTrue(manifest.contains("value: \"cmd.standard.punishment\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_ACCEPTED_USERNAME"));
        assertTrue(manifest.contains("value: \"FulcrumBotOne\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_SECOND_ACCEPTED_USERNAME"));
        assertTrue(manifest.contains("value: \"FulcrumBotTwo\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_USERNAME"));
        assertTrue(manifest.contains("value: \"FulcrumBotFour\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_DISPLAY_NAME"));
        assertTrue(manifest.contains("value: \"Fulcrum Bot Four\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_DENIED_USERNAME"));
        assertTrue(manifest.contains("value: \"FulcrumBannedOne\""));
        assertTrue(manifest.contains("value: \"principal-lobby-capability-seed\""));
        assertTrue(manifest.contains("name: fulcrum-lobby-paper"));
        assertTrue(manifest.contains("kind: Role"));
        assertTrue(manifest.contains("name: fulcrum-paper-agones-sdk"));
        assertTrue(manifest.contains("resources: [\"gameservers\"]"));
        assertTrue(manifest.contains("verbs: [\"get\", \"list\", \"watch\", \"patch\", \"update\"]"));
        assertTrue(manifest.contains("kind: RoleBinding"));
        assertTrue(manifest.contains("name: fulcrum-paper-agent"));
        assertTrue(manifest.contains("replicas: 1"));
        assertTrue(manifest.contains("scheduling: Packed"));
        assertTrue(manifest.contains("maxSurge: 1"));
        assertTrue(manifest.contains("maxUnavailable: 1"));
        assertFalse(manifest.contains("maxUnavailable: 0"));
        assertTrue(manifest.contains("sh.harold.fulcrum/slot-id: \"slot-lobby-shared\""));
        assertTrue(manifest.contains("sh.harold.fulcrum/instance-kind: \"paper\""));
        assertTrue(manifest.contains("sh.harold.fulcrum/principal-id: \"principal-fulcrum-paper-agent\""));
        assertTrue(manifest.contains("portPolicy: Dynamic"));
        assertTrue(manifest.contains("containerPort: 25565"));
        assertTrue(manifest.contains("protocol: TCP"));
        assertTrue(manifest.contains("sdkServer:"));
        assertTrue(manifest.contains("grpcPort: 9357"));
        assertTrue(manifest.contains("httpPort: 9358"));
        assertTrue(manifest.contains("--probe-port=18081"));
        assertTrue(manifest.contains("containerPort: 18081"));
        assertFalse(manifest.contains("--probe-port=8080"));
        assertTrue(manifest.contains("FULCRUM_PAPER_ALLOCATION_FILE: \"/var/fulcrum/paper/fulcrum-allocated-assignment.properties\""));
        assertTrue(manifest.contains("FULCRUM_OBJECT_STORE_MODE: \"s3\""));
        assertTrue(manifest.contains("FULCRUM_OBJECT_STORE_ENDPOINT: \"http://fulcrum-object-store:9000\""));
        assertTrue(manifest.contains("FULCRUM_OBJECT_STORE_REGION: \"us-east-1\""));
        assertTrue(manifest.contains("name: fulcrum-object-store-credentials"));
        assertTrue(manifest.contains("name: FULCRUM_OBJECT_STORE_ACCESS_KEY"));
        assertTrue(manifest.contains("name: FULCRUM_OBJECT_STORE_SECRET_KEY"));
        assertFalse(manifest.contains("FULCRUM_OBJECT_STORE_ROOT"));
        assertFalse(manifest.contains("claimName: fulcrum-object-store"));
        assertTrue(manifest.contains("FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS: \"fulcrum-kafka:9092\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_AGONES_SDK_URL: \"http://127.0.0.1:9358/\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_OBSERVATION_BRIDGE_URL: \"http://127.0.0.1:18080/observations\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL: \"http://127.0.0.1:18083/capabilities\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_REWARD_BRIDGE_URL: \"http://127.0.0.1:18084/rewards\""));
        assertTrue(manifest.contains("FULCRUM_VALKEY_ENDPOINT: \"fulcrum-valkey:6379\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_WORLD_ARTIFACT_ID: \"artifact-lobby-bedrock-world\""));
        assertEquals(
                LobbyWorldArtifactProvisioner.defaultArchiveDigest(),
                configValue(manifest, "FULCRUM_PAPER_WORLD_ARTIFACT_DIGEST"));
        assertTrue(manifest.contains("FULCRUM_PAPER_ROUTE_ID_PREFIX: \"route-velocity-login-\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_SPAWN_WORLD: \"world\""));
        assertTrue(manifest.contains("name: FULCRUM_INSTANCE_ID"));
        assertTrue(manifest.contains("fieldPath: metadata.name"));
        assertTrue(manifest.contains("name: FULCRUM_MACHINE_REF"));
        assertTrue(manifest.contains("fieldPath: spec.nodeName"));
        assertTrue(manifest.contains("name: FULCRUM_POOL_ID"));
        assertTrue(manifest.contains("value: \"pool-lobby\""));
        assertTrue(manifest.contains("name: FULCRUM_PRINCIPAL_ID"));
        assertTrue(manifest.contains("name: MINECRAFT_EULA"));
        assertTrue(manifest.contains("value: \"true\""));
        assertTrue(manifest.contains("name: FULCRUM_TEST_OPERATOR_NAME"));
        assertTrue(manifest.contains("value: \"ZECHEESELORD\""));
        assertTrue(manifest.contains("name: FULCRUM_TEST_OPERATOR_UUID"));
        assertTrue(manifest.contains("value: \"fe85a251-2c9b-3c79-a2eb-2e725d7df55f\""));
        assertTrue(manifest.contains("name: FULCRUM_TEST_OPERATOR_LEVEL"));
        assertTrue(manifest.contains("value: \"4\""));
        assertTrue(manifest.contains("name: FULCRUM_TEST_OPERATOR_BYPASSES_PLAYER_LIMIT"));
        assertTrue(manifest.contains("FULCRUM_PAPER_SESSION_OWNER_TOKEN"));
        assertTrue(manifest.contains("FULCRUM_HOST_OBSERVATION_TOPIC: \"host.observation\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_REWARD_ECONOMY_COMMAND_TOPIC: \"cmd.standard.economy\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_REWARD_STATS_COMMAND_TOPIC: \"cmd.standard.stats\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_REWARD_CURRENCY_KEY: \"coins\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_REWARD_AMOUNT_MINOR_UNITS: \"250\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_REWARD_STAT_KEY: \"session-completions\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_REWARD_DELIVERY_COPIES: \"2\""));
        assertTrue(manifest.contains("apiVersion: autoscaling.agones.dev/v1"));
        assertTrue(manifest.contains("kind: FleetAutoscaler"));
        assertTrue(manifest.contains("fleetName: fulcrum-lobby-paper"));
        assertTrue(manifest.contains("type: Buffer"));
        assertTrue(manifest.contains("bufferSize: 1"));
        assertTrue(manifest.contains("minReplicas: 1"));
        assertTrue(manifest.contains("maxReplicas: 4"));
    }

    @Test
    void lobbyPaperFleetDoesNotExposeCanonicalStoreCredentialsToPaperHosts() throws IOException {
        String manifest = resource("fulcrum/kubernetes/agones/lobby-paper-fleet.yaml");

        assertFalse(manifest.contains("FULCRUM_POSTGRES_"));
        assertFalse(manifest.contains("POSTGRES_PASSWORD"));
        assertFalse(manifest.contains("FULCRUM_CASSANDRA_"));
        assertFalse(manifest.contains("CASSANDRA_USERNAME"));
        assertFalse(manifest.contains("CASSANDRA_PASSWORD"));
    }

    @Test
    void lobbyPaperAllocationClaimsReadyFleetGameServer() throws IOException {
        String allocation = resource("fulcrum/kubernetes/agones/lobby-paper-allocation.yaml");

        assertTrue(allocation.contains("apiVersion: allocation.agones.dev/v1"));
        assertTrue(allocation.contains("kind: GameServerAllocation"));
        assertTrue(allocation.contains("generateName: fulcrum-lobby-paper-"));
        assertTrue(allocation.contains("namespace: fulcrum-lobby"));
        assertTrue(allocation.contains("agones.dev/fleet: fulcrum-lobby-paper"));
        assertTrue(allocation.contains("sh.harold.fulcrum/pool-id: \"pool-lobby\""));
        assertTrue(allocation.contains("gameServerState: Ready"));
        assertTrue(allocation.contains("counters:"));
        assertTrue(allocation.contains("presences:"));
        assertTrue(allocation.contains("minAvailable: 1"));
        assertTrue(allocation.contains("scheduling: Packed"));
        assertTrue(allocation.contains("sh.harold.fulcrum/session-id: \"session-lobby-shared\""));
        assertTrue(allocation.contains("sh.harold.fulcrum/slot-id: \"slot-lobby-shared\""));
        assertTrue(allocation.contains("sh.harold.fulcrum/resolved-manifest-id: \"manifest-lobby-bedrock-v1\""));
        assertTrue(allocation.contains("sh.harold.fulcrum/trace-id: \"trace-paper-agones-phase2-allocation\""));
        assertTrue(allocation.contains("sh.harold.fulcrum/route-id-prefix: \"route-velocity-login-\""));
    }

    @Test
    void lobbySharedShardAllocationPublishesTypedControlCommandAndWaitsForState() throws IOException {
        String manifest = resource("fulcrum/kubernetes/agones/lobby-shared-shard-allocation.yaml");

        assertTrue(manifest.contains("kind: ServiceAccount"));
        assertTrue(manifest.contains("name: fulcrum-shared-shard-allocation-provisioner"));
        assertTrue(manifest.contains("name: fulcrum-lobby-shared-shard-allocation"));
        assertTrue(manifest.contains("sh.harold.fulcrum.distribution.launcher.LobbySharedShardAllocationProvisioner"));
        assertTrue(manifest.contains("name: FULCRUM_KAFKA_BOOTSTRAP_SERVERS"));
        assertTrue(manifest.contains("value: \"fulcrum-kafka:9092\""));
        assertTrue(manifest.contains("name: FULCRUM_SHARED_SHARD_ALLOCATION_COMMAND_TOPIC"));
        assertTrue(manifest.contains("value: \"ctrl.cmd.shared-shard-allocation\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_EXPERIENCE_ID"));
        assertTrue(manifest.contains("value: \"experience-lobby\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_POOL_ID"));
        assertTrue(manifest.contains("value: \"pool-lobby\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_SESSION_ID"));
        assertTrue(manifest.contains("value: \"session-lobby-shared\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_RESOLVED_MANIFEST_ID"));
        assertTrue(manifest.contains("value: \"manifest-lobby-bedrock-v1\""));
        assertTrue(manifest.contains("name: fulcrum-lobby-shared-shard-allocation-materialization"));
        assertTrue(manifest.contains("sh.harold.fulcrum.distribution.launcher.LobbySharedShardAllocationMaterializationVerifier"));
        assertTrue(manifest.contains("name: FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC"));
        assertTrue(manifest.contains("value: \"ctrl.state.shared-shard-allocation\""));
        assertTrue(manifest.contains("name: FULCRUM_LOBBY_ALLOCATION_MATERIALIZATION_TIMEOUT"));
        assertTrue(manifest.contains("value: \"PT180S\""));
    }

    @Test
    void lobbyNamespaceManifestCreatesAgonesWatchedNamespace() throws IOException {
        String namespace = resource("fulcrum/kubernetes/agones/lobby-namespace.yaml");

        assertTrue(namespace.contains("apiVersion: v1"));
        assertTrue(namespace.contains("kind: Namespace"));
        assertTrue(namespace.contains("name: fulcrum-lobby"));
    }

    @Test
    void agonesHelmValuesScopesGameServerNamespace() throws IOException {
        String values = resource("fulcrum/kubernetes/agones/agones-helm-values.yaml");

        assertTrue(values.contains("agones:"));
        assertTrue(values.contains("allocator:"));
        assertTrue(values.contains("disableMTLS: true"));
        assertTrue(values.contains("disableTLS: true"));
        assertTrue(values.contains("gameservers:"));
        assertTrue(values.contains("namespaces:"));
        assertTrue(values.contains("- fulcrum-lobby"));
    }

    @Test
    void lobbyKafkaSubstrateProvidesPaperCommandLogEndpoint() throws IOException {
        String kafka = resource("fulcrum/kubernetes/substrate/lobby-kafka.yaml");

        assertTrue(kafka.contains("kind: Service"));
        assertTrue(kafka.contains("name: fulcrum-kafka"));
        assertTrue(kafka.contains("namespace: fulcrum-lobby"));
        assertTrue(kafka.contains("kind: StatefulSet"));
        assertTrue(kafka.contains("serviceName: fulcrum-kafka"));
        assertTrue(kafka.contains("image: apache/kafka:4.3.0"));
        assertTrue(kafka.contains("name: KAFKA_PROCESS_ROLES"));
        assertTrue(kafka.contains("value: \"broker,controller\""));
        assertTrue(kafka.contains("name: KAFKA_ADVERTISED_LISTENERS"));
        assertTrue(kafka.contains("fulcrum-kafka.fulcrum-lobby.svc.cluster.local:9092"));
        assertTrue(kafka.contains("name: KAFKA_AUTO_CREATE_TOPICS_ENABLE"));
        assertTrue(kafka.contains("value: \"true\""));
        assertTrue(kafka.contains("readinessProbe:"));
        assertTrue(kafka.contains("tcpSocket:"));
        assertTrue(kafka.contains("volumeClaimTemplates:"));
        assertTrue(kafka.contains("name: fulcrum-valkey"));
        assertTrue(kafka.contains("image: valkey/valkey:9.1.0"));
        assertTrue(kafka.contains("containerPort: 6379"));
        assertTrue(kafka.contains("name: fulcrum-object-store-credentials"));
        assertTrue(kafka.contains("FULCRUM_OBJECT_STORE_ACCESS_KEY: \"fulcrum-object-access\""));
        assertTrue(kafka.contains("FULCRUM_OBJECT_STORE_SECRET_KEY: \"fulcrum-object-secret\""));
        assertTrue(kafka.contains("name: fulcrum-object-store"));
        assertTrue(kafka.contains("image: quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z"));
        assertTrue(kafka.contains("containerPort: 9000"));
        assertTrue(kafka.contains("path: /minio/health/ready"));
        assertTrue(kafka.contains("name: object-store-data"));
        assertTrue(kafka.contains("name: fulcrum-postgres-credentials"));
        assertTrue(kafka.contains("name: fulcrum-postgres"));
        assertTrue(kafka.contains("image: postgres:18.4"));
        assertTrue(kafka.contains("containerPort: 5432"));
        assertTrue(kafka.contains("name: fulcrum-cassandra"));
        assertTrue(kafka.contains("image: cassandra:5.0.8"));
        assertTrue(kafka.contains("containerPort: 9042"));
        assertTrue(kafka.contains("name: fulcrum-authority-schema"));
        assertTrue(kafka.contains("sh.harold.fulcrum.distribution.launcher.LobbyAuthoritySchemaProvisioner"));
        assertTrue(kafka.contains("initContainers:"));
        assertTrue(kafka.contains("image: busybox:1.37.0"));
        assertTrue(kafka.contains("name: wait-for-postgres"));
        assertTrue(kafka.contains("name: wait-for-cassandra"));
        assertTrue(kafka.contains("name: fulcrum-authority-service"));
        assertTrue(kafka.contains("serviceAccountName: fulcrum-authority-service"));
        assertTrue(kafka.contains("--role=authority-service"));
        assertTrue(kafka.contains("name: wait-for-kafka"));
        assertTrue(kafka.contains("name: wait-for-valkey"));
        assertTrue(kafka.contains("name: fulcrum-controller-service"));
        assertTrue(kafka.contains("serviceAccountName: fulcrum-controller-service"));
        assertTrue(kafka.contains("--role=controller-service"));
        assertTrue(kafka.contains("name: wait-for-agones-allocator"));
        assertTrue(kafka.contains("name: fulcrum-worker-agent"));
        assertTrue(kafka.contains("serviceAccountName: fulcrum-worker-agent"));
        assertTrue(kafka.contains("--role=worker-agent"));
        assertTrue(kafka.contains("name: wait-for-object-store"));
        assertTrue(kafka.contains("name: FULCRUM_WORKER_KAFKA_BOOTSTRAP_SERVERS"));
        assertTrue(kafka.contains("name: FULCRUM_WORKER_JOB_TOPIC"));
        assertTrue(kafka.contains("value: \"worker.jobs\""));
        assertTrue(kafka.contains("name: FULCRUM_WORKER_RESULT_TOPIC"));
        assertTrue(kafka.contains("value: \"worker.results\""));
        assertTrue(kafka.contains("name: FULCRUM_WORKER_OBJECT_BUCKET"));
        assertTrue(kafka.contains("value: \"worker-results\""));
        assertTrue(kafka.contains("name: FULCRUM_OBJECT_STORE_MODE"));
        assertTrue(kafka.contains("value: \"s3\""));
        assertTrue(kafka.contains("name: FULCRUM_OBJECT_STORE_ENDPOINT"));
        assertTrue(kafka.contains("value: \"http://fulcrum-object-store:9000\""));
        assertTrue(kafka.contains("name: FULCRUM_OBJECT_STORE_REGION"));
        assertTrue(kafka.contains("value: \"us-east-1\""));
        assertTrue(kafka.contains("service-account:fulcrum-worker-agent"));
        assertTrue(kafka.contains("name: FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS"));
        assertTrue(kafka.contains("value: \"fulcrum-kafka:9092\""));
        assertTrue(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_URL"));
        assertTrue(kafka.contains("value: \"http://agones-allocator.agones-system.svc.cluster.local:443\""));
        assertTrue(kafka.contains("name: FULCRUM_AGONES_NAMESPACE"));
        assertTrue(kafka.contains("value: \"fulcrum-lobby\""));
        assertFalse(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_CLIENT_CERT_PATH"));
        assertFalse(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_CLIENT_KEY_PATH"));
        assertFalse(kafka.contains("secretName: allocator-client.default"));
        assertFalse(kafka.contains("agones-allocator-client"));
        assertFalse(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_CA_CERT_PATH"));
        assertFalse(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_DISABLE_HOSTNAME_VERIFICATION"));
        assertFalse(kafka.contains("secretName: fulcrum-agones-allocator-tls-ca"));
        assertTrue(kafka.contains("name: FULCRUM_CONTROL_STATE_TOPIC"));
        assertTrue(kafka.contains("value: \"ctrl.state\""));
        assertTrue(kafka.contains("name: FULCRUM_HOST_COMMAND_TOPIC"));
        assertTrue(kafka.contains("value: \"host.paper.commands\""));
        assertTrue(kafka.contains("name: FULCRUM_HOST_OBSERVATION_TOPIC"));
        assertTrue(kafka.contains("value: \"host.observation\""));
        assertTrue(kafka.contains("name: FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC"));
        assertTrue(kafka.contains("value: \"host.velocity.routes\""));
        assertTrue(kafka.contains("name: FULCRUM_POSTGRES_JDBC_URL"));
        assertTrue(kafka.contains("value: \"jdbc:postgresql://fulcrum-postgres:5432/fulcrum\""));
        assertTrue(kafka.contains("name: FULCRUM_CASSANDRA_CONTACT_POINTS"));
        assertTrue(kafka.contains("value: \"fulcrum-cassandra:9042\""));
        assertTrue(kafka.contains("name: FULCRUM_VALKEY_ENDPOINT"));
    }

    @Test
    void agonesResourceReadmeDocumentsGradleDeployPath() throws IOException {
        String readme = resource("fulcrum/kubernetes/agones/README.md");

        assertTrue(readme.contains(".\\gradlew.bat clusterE2e"));
        assertTrue(readme.contains(".\\gradlew.bat clusterK3sE2e"));
        assertTrue(readme.contains(".\\gradlew.bat clusterExistingE2e"));
        assertTrue(readme.contains("creates a generated k3d cluster"));
        assertTrue(readme.contains("default or a kind cluster"));
        assertTrue(readme.contains("service-launcher, Paper GameServer, and Velocity proxy images"));
        assertTrue(readme.contains("into that cluster"));
        assertTrue(readme.contains("existing-cluster path is exposed separately"));
        assertTrue(readme.contains("runs the lobby"));
        assertTrue(readme.contains("cluster verifier against the public Minecraft endpoint"));
        assertTrue(readme.contains("public Minecraft endpoint"));
        assertTrue(readme.contains(":distribution:service-launcher:paperAgonesPhase2Deploy"));
        assertTrue(readme.contains("-Pfulcrum.kubeContext=<context>"));
        assertTrue(readme.contains("-Pfulcrum.kubeconfig=<path>"));
        assertTrue(readme.contains("this takes precedence over `-Pfulcrum.kubeContext`"));
        assertTrue(readme.contains("ghcr.io/sh-harold/fulcrum-service-launcher:dev"));
        assertTrue(readme.contains("ghcr.io/sh-harold/fulcrum-paper-gameserver:dev"));
        assertTrue(readme.contains("ghcr.io/sh-harold/fulcrum-velocity-proxy:dev"));
        assertTrue(readme.contains("-Pfulcrum.serviceLauncherImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.paperGameserverImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.velocityProxyImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.kafkaImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.postgresImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.cassandraImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.valkeyImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.objectStoreImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.clusterProvider=k3d"));
        assertTrue(readme.contains("-Pfulcrum.k3dImage=rancher/k3s:v1.34.7-k3s1"));
        assertTrue(readme.contains("-Pfulcrum.clusterName=fulcrum-cluster-e2e"));
        assertTrue(readme.contains("-Pfulcrum.clusterApiPort=16443"));
        assertTrue(readme.contains("-Pfulcrum.clusterMinecraftPort=25565"));
        assertTrue(readme.contains("-Pfulcrum.keepCluster=true"));
        assertTrue(readme.contains("cgroup v1"));
        assertTrue(readme.contains("-Pfulcrum.k3dImage="));
        assertTrue(readme.contains("clusterK3sStart"));
        assertTrue(readme.contains("clusterK3sImportImages"));
        assertTrue(readme.contains("clusterK3sStop"));
        assertTrue(readme.contains("build/cluster-e2e/kubeconfig.yaml"));
        assertTrue(readme.contains("k3d image import"));
        assertTrue(readme.contains("kind load docker-image"));
        assertTrue(readme.contains("generated cluster"));
        assertTrue(readme.contains("already exists with the configured name"));
        assertTrue(readme.contains("deletes and recreates"));
        assertTrue(readme.contains("paperAgonesRenderManifests"));
        assertTrue(readme.contains("effective image tags"));
        assertTrue(readme.contains("kubectl apply"));
        assertTrue(readme.contains("fulcrum-lobby"));
        assertTrue(readme.contains("lobby-paper-allocation.yaml"));
        assertTrue(readme.contains("lobby-namespace.yaml"));
        assertTrue(readme.contains("agones-helm-values.yaml"));
        assertTrue(readme.contains("generated and"));
        assertTrue(readme.contains("existing-cluster gates use host Helm"));
        assertTrue(readme.contains("../substrate/lobby-kafka.yaml"));
        assertTrue(readme.contains("fulcrum-kafka:9092"));
        assertTrue(readme.contains("fulcrum-valkey:6379"));
        assertTrue(readme.contains("fulcrum-object-store:9000"));
        assertTrue(readme.contains("fulcrum-postgres:5432"));
        assertTrue(readme.contains("fulcrum-cassandra:9042"));
        assertTrue(readme.contains("fulcrum-authority-service"));
        assertTrue(readme.contains("fulcrum-controller-service"));
        assertTrue(readme.contains("fulcrum-worker-agent"));
        assertTrue(readme.contains("local generated cluster gate"));
        assertTrue(readme.contains("DISABLE_MTLS=true"));
        assertTrue(readme.contains("DISABLE_TLS=true"));
        assertTrue(readme.contains("in-cluster Service over HTTP"));
        assertTrue(readme.contains("Java 26"));
        assertTrue(readme.contains("worker.jobs"));
        assertTrue(readme.contains("worker.results"));
        assertTrue(readme.contains("paperAgonesConfigureAllocatorTls"));
        assertTrue(readme.contains("paperAgonesApplySubstrate"));
        assertTrue(readme.contains("paperAgonesWaitForKafka"));
        assertTrue(readme.contains("paperAgonesWaitForValkey"));
        assertTrue(readme.contains("paperAgonesWaitForObjectStorage"));
        assertTrue(readme.contains("paperAgonesWaitForPostgres"));
        assertTrue(readme.contains("paperAgonesWaitForCassandra"));
        assertTrue(readme.contains("paperAgonesWaitForAuthoritySchema"));
        assertTrue(readme.contains("paperAgonesWaitForAuthorityService"));
        assertTrue(readme.contains("paperAgonesSyncAllocatorCa"));
        assertTrue(readme.contains("strict-TLS parity checks"));
        assertTrue(readme.contains("paperAgonesWaitForControllerService"));
        assertTrue(readme.contains("paperAgonesWaitForWorkerAgent"));
        assertTrue(readme.contains("paperAgonesInstallAgones"));
        assertTrue(readme.contains("Docker daemon access"));
        assertTrue(readme.contains("paperAgonesClusterPreflight"));
        assertTrue(readme.contains("Docker engine"));
        assertTrue(readme.contains("host Helm is not on `PATH`"));
        assertTrue(readme.contains("Kubernetes context or generated kubeconfig is missing"));
        assertTrue(readme.contains("lobby-shared-shard-allocation.yaml"));
        assertTrue(readme.contains("ctrl.cmd.shared-shard-allocation"));
        assertTrue(readme.contains("ctrl.state.shared-shard-allocation"));
        assertTrue(readme.contains("paperAgonesApplySharedShardAllocation"));
        assertTrue(readme.contains("paperAgonesWaitForSharedShardAllocation"));
        assertTrue(readme.contains("paperAgonesWaitForSharedShardAllocationState"));
        assertTrue(readme.contains("paperAgonesRestartControllerServiceForReplay"));
        assertTrue(readme.contains("shared-shard allocation replay from"));
        assertTrue(readme.contains("route-velocity-login-"));
        assertTrue(readme.contains("host attach observations can acknowledge the RouteAttempt"));
        assertTrue(readme.contains("MINECRAFT_EULA=true"));
        assertTrue(readme.contains("runtime probe on port `18081`"));
        assertTrue(readme.contains("sidecar health server on port"));
        assertTrue(readme.contains("Paper Fleet manifest must not expose PostgreSQL or Cassandra canonical store"));
        assertTrue(readme.contains("with the `agones`"));
        assertTrue(readme.contains("https://agones.dev/chart/stable"));
        assertTrue(readme.contains("helm upgrade --install"));
        assertTrue(readme.contains("explicit Agones readiness gates"));
        assertTrue(readme.contains("-Pfulcrum.agonesChartVersion=1.58.0"));
        assertTrue(readme.contains("-Pfulcrum.agonesReleaseName=agones"));
        assertTrue(readme.contains("-Pfulcrum.agonesSystemNamespace=agones-system"));
        assertTrue(readme.contains("paperAgonesAllocateLobby"));
        assertTrue(readme.contains("Allocated"));
        assertTrue(readme.contains("status.gameServerName"));
        assertFalse(readme.contains("helm.cattle.io/v1"));
        assertFalse(readme.contains("--cgroupns"));
    }

    @Test
    void gradleDeployHelpersPreferGeneratedKubeconfigOverCurrentContext() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("val kubeconfig = providers.gradleProperty(\"fulcrum.kubeconfig\")"));
        assertTrue(build.contains("val generatedClusterKubeconfig = layout.buildDirectory.file(\"cluster-e2e/kubeconfig.yaml\")"));
        assertTrue(build.contains("val generatedClusterRequested = providers.provider"));
        assertTrue(build.contains("fun effectiveKubeconfigPath(): String?"));
        assertTrue(build.contains("fun verifierKubeArgs(runArgs: MutableList<String>)"));
        assertTrue(build.contains("add(\"--kubeconfig\")"));
        assertTrue(build.contains("rootProject.file(it).absolutePath"));
        assertTrue(build.contains("runArgs.add(\"--kubeconfig=$explicitKubeconfig\")"));
        assertTrue(build.contains("?: if (generatedClusterRequested.get()) generatedClusterKubeconfig.get().asFile.absolutePath else null"));
        assertTrue(build.contains("} else {\n        kubeContext.orNull?.takeIf { it.isNotBlank() }?.let {"));
        assertTrue(build.contains("fun selectedLobbyEndpointHost(): String?"));
        assertTrue(build.contains("selectedLobbyEndpointHost()?.let {"));
        assertTrue(build.contains("runArgs.add(\"--endpoint-host=$it\")"));
        assertTrue(build.contains("if (generatedClusterRequested.get())"));
        assertTrue(build.contains("\"--verify-agones-fleet-state=true\""));
        assertTrue(build.contains("\"--verify-route-attempt-state=true\""));
    }

    @Test
    void gradleRegistersGeneratedClusterLifecycle() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("val clusterProvider = providers.gradleProperty(\"fulcrum.clusterProvider\")"));
        assertTrue(build.contains(".orElse(\"k3d\")"));
        assertTrue(build.contains("val clusterK3dImage = providers.gradleProperty(\"fulcrum.k3dImage\")"));
        assertTrue(build.contains(".orElse(\"rancher/k3s:${libs.versions.k3dK3s.get()}\")"));
        assertTrue(build.contains("val clusterName = providers.gradleProperty(\"fulcrum.clusterName\")"));
        assertTrue(build.contains("val clusterApiPort = providers.gradleProperty(\"fulcrum.clusterApiPort\")"));
        assertTrue(build.contains("val clusterMinecraftPort = providers.gradleProperty(\"fulcrum.clusterMinecraftPort\")"));
        assertTrue(build.contains("val clusterCreateTimeout = providers.gradleProperty(\"fulcrum.clusterCreateTimeout\")"));
        assertTrue(build.contains("val keepGeneratedCluster = providers.gradleProperty(\"fulcrum.keepCluster\")"));
        assertTrue(build.contains("fun k3dCommand(vararg args: String)"));
        assertTrue(build.contains("fun kindCommand(vararg args: String)"));
        assertTrue(build.contains("fun generatedClusterCli(vararg args: String)"));
        assertTrue(build.contains("\"k3d\" -> k3dCommand(*args)"));
        assertTrue(build.contains("\"kind\" -> kindCommand(*args)"));
        assertTrue(build.contains("fun generatedClusterExists(): Boolean"));
        assertTrue(build.contains("fun deleteGeneratedClusterIfExists(reason: String)"));
        assertTrue(build.contains("fun reserveFreeHostPort(): Int"));
        assertTrue(build.contains("fun parseHostPort(propertyName: String, value: String): Int"));
        assertTrue(build.contains("fun requireHostPortAvailable(label: String, port: Int)"));
        assertTrue(build.contains("fun requireConfiguredClusterHostPortsAvailable()"));
        assertTrue(build.contains("it does not kill arbitrary host processes"));
        assertTrue(build.contains("Using generated $provider Kubernetes API port 127.0.0.1:$selectedApiHostPort"));
        assertTrue(build.contains("tasks.register(\"clusterK3sPreflight\")"));
        assertTrue(build.contains("tasks.register(\"clusterK3sDeleteExisting\")"));
        assertTrue(build.contains("tasks.register(\"clusterK3sStart\")"));
        assertTrue(build.contains("tasks.register(\"clusterK3sImportImages\")"));
        assertTrue(build.contains("tasks.register(\"clusterK3sStop\")"));
        assertTrue(build.contains("tasks.register(\"clusterK3sE2e\")"));
        assertTrue(build.contains("deleteGeneratedClusterIfExists(\"Deleting existing\")"));
        assertTrue(build.contains("requireConfiguredClusterHostPortsAvailable()"));
        assertTrue(build.contains("requireHostPortAvailable(\"Generated Minecraft\", selectedMinecraftHostPort)"));
        assertTrue(build.contains("createArgs.add(\"--image\")"));
        assertTrue(build.contains("\"--kubeconfig-update-default=false\""));
        assertTrue(build.contains("k3dCommand(\"kubeconfig\", \"get\", name)"));
        assertTrue(build.contains(".replace(\"https://host.docker.internal:\", \"https://127.0.0.1:\")"));
        assertTrue(build.contains("kindCommand("));
        assertTrue(build.contains("\"create\","));
        assertTrue(build.contains("val agonesImages = agonesRuntimeImages()"));
        assertTrue(build.contains("dockerCommand(\"image\", \"inspect\", image)"));
        assertTrue(build.contains("dockerCommand(\"pull\", image)"));
        assertTrue(build.contains("k3dCommand(\"image\", \"import\", *fulcrumImages.toTypedArray(), \"--cluster\", clusterName.get())"));
        assertTrue(build.contains("k3dCommand(\"image\", \"import\", *agonesImages.toTypedArray(), \"--cluster\", clusterName.get())"));
        assertTrue(build.contains("kindCommand(\"load\", \"docker-image\", \"--name\", clusterName.get(), *images.toTypedArray())"));
        assertTrue(build.contains("fun waitForAgonesCrds(label: String)"));
        assertTrue(build.contains("\"gameserversets.agones.dev\""));
        assertTrue(build.contains("val requiredAgonesApiResources = mapOf("));
        assertTrue(build.contains("\"allocation.agones.dev\" to \"gameserverallocations.allocation.agones.dev\""));
        assertTrue(build.contains("\"api-resources\", \"--api-group=$group\", \"-o\", \"name\""));
        assertFalse(build.contains("\"fleetautoscalers.autoscaling.agones.dev\",\n    \"gameserverallocations.allocation.agones.dev\""));
        assertTrue(build.contains("helmCommand("));
        assertTrue(build.contains("\"repo\", \"add\", \"agones\", \"https://agones.dev/chart/stable\", \"--force-update\""));
        assertTrue(build.contains("\"repo\", \"update\", \"agones\""));
        assertTrue(build.contains("\"upgrade\","));
        assertTrue(build.contains("\"--force-conflicts\""));
        assertTrue(build.contains("waitForAgonesCrds(\"host Helm Agones install\")"));
        assertFalse(build.contains("apiVersion: helm.cattle.io/v1"));
        assertFalse(build.contains("kind: HelmChart"));
        assertFalse(build.contains("renderedAgonesHelmChart"));
        assertFalse(build.contains("\"ctr\""));
        assertTrue(build.contains("tasks.register(\"paperAgonesConfigureAllocatorTls\")"));
        assertTrue(build.contains("\"deployment/agones-allocator\""));
        assertFalse(build.contains("\"DISABLE_MTLS=true\""));
        assertTrue(build.contains("\"deployment/agones-controller\""));
        assertTrue(build.contains("\"rollout\",\n                \"status\",\n                \"deployment/agones-allocator\""));
        assertTrue(build.contains("\"fleets.agones.dev,\""));
        assertTrue(build.contains("\"fleetautoscalers.autoscaling.agones.dev,\""));
        assertTrue(build.contains("\"gameservers.agones.dev\""));
        assertTrue(build.contains("tasks.register(\"paperAgonesDeleteSharedShardAllocationJobs\")"));
        assertTrue(build.contains("\"fulcrum-lobby-shared-shard-allocation-materialization\""));
        assertTrue(build.contains("\"--ignore-not-found=true\""));
        assertTrue(build.contains("tasks.named(\"paperAgonesDeleteSharedShardAllocationJobs\")"));
        assertTrue(build.contains("inputs.property(\"serviceLauncherImageTag\", serviceLauncherImageTag)"));
        assertTrue(build.contains("inputs.property(\"paperGameserverImageTag\", paperGameserverImageTag)"));
        assertTrue(build.contains("inputs.property(\"velocityProxyImageTag\", velocityProxyImageTag)"));
        assertTrue(build.contains("inputs.property(\"lobbyTargetCapacity\", lobbyTargetCapacity)"));
        assertTrue(build.contains("inputs.property(\"lobbyHardCapacity\", lobbyHardCapacity)"));
        assertTrue(build.contains("mustRunAfter(tasks.named(\"clusterK3sImportImages\"))"));
        assertTrue(build.contains("finalizedBy(tasks.named(\"clusterK3sStop\"))"));
    }

    @Test
    void rootClusterE2eUsesCanonicalGeneratedProfile() throws IOException {
        String rootBuild = Files.readString(Path.of("..", "..", "build.gradle.kts"));

        assertTrue(rootBuild.contains("tasks.register(\"clusterExistingE2e\")"));
        assertTrue(rootBuild.contains("tasks.register(\"clusterK3sE2e\")"));
        assertTrue(rootBuild.contains("dependsOn(\":distribution:service-launcher:clusterK3sE2e\")"));
        assertTrue(rootBuild.contains("description = \"Runs the canonical k3d-backed cluster E2E gate"));
        assertTrue(rootBuild.contains("tasks.register(\"clusterE2e\")"));
        assertTrue(rootBuild.contains("dependsOn(\"clusterK3sE2e\")"));
    }

    @Test
    void gradleSubstrateRenderingUsesCentralImagePinsAndOverrides() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("val defaultKafkaImage = \"apache/kafka:${libs.versions.kafka.get()}\""));
        assertTrue(build.contains("val kafkaImageTag = providers.gradleProperty(\"fulcrum.kafkaImage\")"));
        assertTrue(build.contains("val defaultPostgresImage = \"postgres:${libs.versions.postgresImage.get()}\""));
        assertTrue(build.contains("val postgresImageTag = providers.gradleProperty(\"fulcrum.postgresImage\")"));
        assertTrue(build.contains("val defaultCassandraImage = \"cassandra:${libs.versions.cassandraImage.get()}\""));
        assertTrue(build.contains("val cassandraImageTag = providers.gradleProperty(\"fulcrum.cassandraImage\")"));
        assertTrue(build.contains("val defaultValkeyImage = \"valkey/valkey:${libs.versions.valkeyImage.get()}\""));
        assertTrue(build.contains("val valkeyImageTag = providers.gradleProperty(\"fulcrum.valkeyImage\")"));
        assertTrue(build.contains("val objectStoreImageTag = providers.gradleProperty(\"fulcrum.objectStoreImage\")"));
        assertTrue(build.contains("val defaultDependencyWaitImage = \"busybox:${libs.versions.busybox.get()}\""));
        assertTrue(build.contains("val dependencyWaitImageTag = providers.gradleProperty(\"fulcrum.dependencyWaitImage\")"));
        assertTrue(build.contains("inputs.property(\"kafkaImageTag\", kafkaImageTag)"));
        assertTrue(build.contains("inputs.property(\"postgresImageTag\", postgresImageTag)"));
        assertTrue(build.contains("inputs.property(\"cassandraImageTag\", cassandraImageTag)"));
        assertTrue(build.contains("inputs.property(\"valkeyImageTag\", valkeyImageTag)"));
        assertTrue(build.contains("inputs.property(\"objectStoreImageTag\", objectStoreImageTag)"));
        assertTrue(build.contains("inputs.property(\"dependencyWaitImageTag\", dependencyWaitImageTag)"));
        assertTrue(build.contains(".replace(defaultKafkaImage, kafkaImageTag.get())"));
        assertTrue(build.contains(".replace(defaultPostgresImage, postgresImageTag.get())"));
        assertTrue(build.contains(".replace(defaultCassandraImage, cassandraImageTag.get())"));
        assertTrue(build.contains(".replace(defaultValkeyImage, valkeyImageTag.get())"));
        assertTrue(build.contains(".replace(defaultObjectStoreImage, objectStoreImageTag.get())"));
        assertTrue(build.contains(".replace(defaultDependencyWaitImage, dependencyWaitImageTag.get())"));
    }

    private static String resource(String name) throws IOException {
        try (var stream = AgonesDeploymentManifestTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String configValue(String manifest, String key) {
        String prefix = key + ": \"";
        int start = manifest.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError("Missing ConfigMap value " + key);
        }
        int valueStart = start + prefix.length();
        int valueEnd = manifest.indexOf('"', valueStart);
        if (valueEnd < 0) {
            throw new AssertionError("Unterminated ConfigMap value " + key);
        }
        return manifest.substring(valueStart, valueEnd);
    }
}
