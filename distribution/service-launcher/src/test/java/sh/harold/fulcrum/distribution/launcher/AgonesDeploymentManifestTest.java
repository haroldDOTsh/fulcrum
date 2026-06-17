package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(manifest.contains("replicas: 1"));
        assertTrue(manifest.contains("scheduling: Packed"));
        assertTrue(manifest.contains("sh.harold.fulcrum/slot-id: \"slot-lobby-shared\""));
        assertTrue(manifest.contains("sh.harold.fulcrum/instance-kind: \"paper\""));
        assertTrue(manifest.contains("sh.harold.fulcrum/principal-id: \"principal-fulcrum-paper-agent\""));
        assertTrue(manifest.contains("portPolicy: Dynamic"));
        assertTrue(manifest.contains("containerPort: 25565"));
        assertTrue(manifest.contains("protocol: TCP"));
        assertTrue(manifest.contains("sdkServer:"));
        assertTrue(manifest.contains("grpcPort: 9357"));
        assertTrue(manifest.contains("httpPort: 9358"));
        assertTrue(manifest.contains("FULCRUM_PAPER_ALLOCATION_FILE: \"/var/fulcrum/paper/fulcrum-allocated-assignment.properties\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS: \"fulcrum-kafka:9092\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_AGONES_SDK_URL: \"http://127.0.0.1:9358/\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_OBSERVATION_BRIDGE_URL: \"http://127.0.0.1:18080/observations\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL: \"http://127.0.0.1:18083/capabilities\""));
        assertTrue(manifest.contains("FULCRUM_VALKEY_ENDPOINT: \"fulcrum-valkey:6379\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_WORLD_ARTIFACT_ID: \"artifact-lobby-bedrock-world\""));
        assertEquals(
                LobbyWorldArtifactProvisioner.defaultArchiveDigest(),
                configValue(manifest, "FULCRUM_PAPER_WORLD_ARTIFACT_DIGEST"));
        assertTrue(manifest.contains("FULCRUM_PAPER_ROUTE_ID_PREFIX: \"route-paper-\""));
        assertTrue(manifest.contains("FULCRUM_PAPER_SPAWN_WORLD: \"world\""));
        assertTrue(manifest.contains("name: FULCRUM_INSTANCE_ID"));
        assertTrue(manifest.contains("fieldPath: metadata.name"));
        assertTrue(manifest.contains("name: FULCRUM_MACHINE_REF"));
        assertTrue(manifest.contains("fieldPath: spec.nodeName"));
        assertTrue(manifest.contains("name: FULCRUM_POOL_ID"));
        assertTrue(manifest.contains("value: \"pool-lobby\""));
        assertTrue(manifest.contains("name: FULCRUM_PRINCIPAL_ID"));
        assertTrue(manifest.contains("FULCRUM_PAPER_SESSION_OWNER_TOKEN"));
        assertTrue(manifest.contains("FULCRUM_HOST_OBSERVATION_TOPIC: \"host.observation\""));
        assertTrue(manifest.contains("apiVersion: autoscaling.agones.dev/v1"));
        assertTrue(manifest.contains("kind: FleetAutoscaler"));
        assertTrue(manifest.contains("fleetName: fulcrum-lobby-paper"));
        assertTrue(manifest.contains("type: Buffer"));
        assertTrue(manifest.contains("bufferSize: 1"));
        assertTrue(manifest.contains("minReplicas: 1"));
        assertTrue(manifest.contains("maxReplicas: 4"));
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
        assertTrue(allocation.contains("sh.harold.fulcrum/route-id-prefix: \"route-paper-\""));
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
        assertTrue(kafka.contains("image: apache/kafka-native:4.3.0"));
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
        assertTrue(kafka.contains("name: fulcrum-postgres-credentials"));
        assertTrue(kafka.contains("name: fulcrum-postgres"));
        assertTrue(kafka.contains("image: postgres:18.2"));
        assertTrue(kafka.contains("containerPort: 5432"));
        assertTrue(kafka.contains("name: fulcrum-cassandra"));
        assertTrue(kafka.contains("image: cassandra:5.0.8"));
        assertTrue(kafka.contains("containerPort: 9042"));
        assertTrue(kafka.contains("name: fulcrum-authority-schema"));
        assertTrue(kafka.contains("sh.harold.fulcrum.distribution.launcher.LobbyAuthoritySchemaProvisioner"));
        assertTrue(kafka.contains("name: fulcrum-authority-service"));
        assertTrue(kafka.contains("serviceAccountName: fulcrum-authority-service"));
        assertTrue(kafka.contains("--role=authority-service"));
        assertTrue(kafka.contains("name: fulcrum-controller-service"));
        assertTrue(kafka.contains("serviceAccountName: fulcrum-controller-service"));
        assertTrue(kafka.contains("--role=controller-service"));
        assertTrue(kafka.contains("name: FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS"));
        assertTrue(kafka.contains("value: \"fulcrum-kafka:9092\""));
        assertTrue(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_URL"));
        assertTrue(kafka.contains("value: \"https://agones-allocator.agones-system.svc.cluster.local\""));
        assertTrue(kafka.contains("name: FULCRUM_AGONES_NAMESPACE"));
        assertTrue(kafka.contains("value: \"fulcrum-lobby\""));
        assertTrue(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_CLIENT_CERT_PATH"));
        assertTrue(kafka.contains("value: \"/var/run/secrets/fulcrum/agones-allocator/client/tls.crt\""));
        assertTrue(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_CLIENT_KEY_PATH"));
        assertTrue(kafka.contains("value: \"/var/run/secrets/fulcrum/agones-allocator/client/tls.key\""));
        assertTrue(kafka.contains("name: FULCRUM_AGONES_ALLOCATOR_CA_CERT_PATH"));
        assertTrue(kafka.contains("value: \"/var/run/secrets/fulcrum/agones-allocator/ca/tls-ca.crt\""));
        assertTrue(kafka.contains("secretName: allocator-client.default"));
        assertTrue(kafka.contains("secretName: fulcrum-agones-allocator-tls-ca"));
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
        assertTrue(readme.contains("lobby cluster verifier"));
        assertTrue(readme.contains("public Minecraft endpoint"));
        assertTrue(readme.contains(":distribution:service-launcher:paperAgonesPhase2Deploy"));
        assertTrue(readme.contains("-Pfulcrum.kubeContext=<context>"));
        assertTrue(readme.contains("ghcr.io/sh-harold/fulcrum-service-launcher:dev"));
        assertTrue(readme.contains("ghcr.io/sh-harold/fulcrum-paper-gameserver:dev"));
        assertTrue(readme.contains("-Pfulcrum.serviceLauncherImage=<image-ref>"));
        assertTrue(readme.contains("-Pfulcrum.paperGameserverImage=<image-ref>"));
        assertTrue(readme.contains("paperAgonesRenderManifests"));
        assertTrue(readme.contains("with the effective image tags before `kubectl apply`"));
        assertTrue(readme.contains("kubectl apply"));
        assertTrue(readme.contains("fulcrum-lobby"));
        assertTrue(readme.contains("lobby-paper-allocation.yaml"));
        assertTrue(readme.contains("lobby-namespace.yaml"));
        assertTrue(readme.contains("agones-helm-values.yaml"));
        assertTrue(readme.contains("../substrate/lobby-kafka.yaml"));
        assertTrue(readme.contains("fulcrum-kafka:9092"));
        assertTrue(readme.contains("fulcrum-valkey:6379"));
        assertTrue(readme.contains("fulcrum-postgres:5432"));
        assertTrue(readme.contains("fulcrum-cassandra:9042"));
        assertTrue(readme.contains("fulcrum-authority-service"));
        assertTrue(readme.contains("fulcrum-controller-service"));
        assertTrue(readme.contains("paperAgonesApplySubstrate"));
        assertTrue(readme.contains("paperAgonesWaitForKafka"));
        assertTrue(readme.contains("paperAgonesWaitForValkey"));
        assertTrue(readme.contains("paperAgonesWaitForPostgres"));
        assertTrue(readme.contains("paperAgonesWaitForCassandra"));
        assertTrue(readme.contains("paperAgonesWaitForAuthoritySchema"));
        assertTrue(readme.contains("paperAgonesWaitForAuthorityService"));
        assertTrue(readme.contains("paperAgonesSyncAllocatorCa"));
        assertTrue(readme.contains("paperAgonesWaitForControllerService"));
        assertTrue(readme.contains("paperAgonesInstallAgones"));
        assertTrue(readme.contains("lobby-shared-shard-allocation.yaml"));
        assertTrue(readme.contains("ctrl.cmd.shared-shard-allocation"));
        assertTrue(readme.contains("ctrl.state.shared-shard-allocation"));
        assertTrue(readme.contains("paperAgonesApplySharedShardAllocation"));
        assertTrue(readme.contains("paperAgonesWaitForSharedShardAllocation"));
        assertTrue(readme.contains("paperAgonesWaitForSharedShardAllocationState"));
        assertTrue(readme.contains("agones/agones"));
        assertTrue(readme.contains("https://agones.dev/chart/stable"));
        assertTrue(readme.contains("-Pfulcrum.agonesChartVersion=1.58.0"));
        assertTrue(readme.contains("-Pfulcrum.agonesReleaseName=agones"));
        assertTrue(readme.contains("-Pfulcrum.agonesSystemNamespace=agones-system"));
        assertTrue(readme.contains("paperAgonesAllocateLobby"));
        assertTrue(readme.contains("Allocated"));
        assertTrue(readme.contains("status.gameServerName"));
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
