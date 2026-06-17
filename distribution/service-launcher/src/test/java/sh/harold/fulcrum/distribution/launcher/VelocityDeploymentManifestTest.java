package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityDeploymentManifestTest {
    @Test
    void lobbyVelocityManifestDeclaresProxyDeploymentAndL4Service() throws IOException {
        String manifest = resource("fulcrum/kubernetes/velocity/lobby-velocity.yaml");

        assertTrue(manifest.contains("kind: ServiceAccount"));
        assertTrue(manifest.contains("name: fulcrum-velocity-agent"));
        assertTrue(manifest.contains("kind: Deployment"));
        assertTrue(manifest.contains("name: fulcrum-velocity"));
        assertTrue(manifest.contains("replicas: 1"));
        assertTrue(manifest.contains("image: ghcr.io/sh-harold/fulcrum-velocity-proxy:dev"));
        assertTrue(manifest.contains("containerPort: 25565"));
        assertTrue(manifest.contains("protocol: TCP"));
        assertTrue(manifest.contains("FULCRUM_VELOCITY_SERVER_ROOT: \"/opt/fulcrum/velocity\""));
        assertTrue(manifest.contains("FULCRUM_VELOCITY_KAFKA_BOOTSTRAP_SERVERS: \"fulcrum-kafka:9092\""));
        assertTrue(manifest.contains("FULCRUM_VELOCITY_ROUTE_BRIDGE_URL: \"http://127.0.0.1:18081/routes\""));
        assertTrue(manifest.contains("FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL: \"http://127.0.0.1:18082/login-gate\""));
        assertTrue(manifest.contains("FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC: \"host.velocity.routes\""));
        assertTrue(manifest.contains("FULCRUM_ROUTE_COMMAND_TOPIC: \"cmd.route\""));
        assertTrue(manifest.contains("FULCRUM_PRESENCE_COMMAND_TOPIC: \"cmd.presence\""));
        assertTrue(manifest.contains("FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC: \"ctrl.cmd.shared-shard-placement\""));
        assertTrue(manifest.contains("FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC: \"ctrl.cmd.route-attempt\""));
        assertTrue(manifest.contains("FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC: \"ctrl.state.shared-shard-allocation\""));
        assertTrue(manifest.contains("FULCRUM_LOBBY_EXPERIENCE_ID: \"experience-lobby\""));
        assertTrue(manifest.contains("FULCRUM_LOBBY_POOL_ID: \"pool-lobby\""));
        assertTrue(manifest.contains("FULCRUM_LOBBY_AGONES_FLEET_NAME: \"fulcrum-lobby-paper\""));
        assertTrue(manifest.contains("FULCRUM_LOBBY_TARGET_CAPACITY: \"75\""));
        assertTrue(manifest.contains("FULCRUM_LOBBY_HARD_CAPACITY: \"150\""));
        assertTrue(manifest.contains("FULCRUM_LOBBY_RESOLVED_MANIFEST_ID: \"manifest-lobby-bedrock-v1\""));
        assertTrue(manifest.contains("FULCRUM_LOBBY_CAPABILITY_SCOPE_FINGERPRINT: \"capability-scope-lobby\""));
        assertTrue(manifest.contains("FULCRUM_LOGIN_GATE_SCOPE: \"experience-lobby\""));
        assertTrue(manifest.contains("FULCRUM_VELOCITY_PRESENCE_LEASE: \"PT5M\""));
        assertTrue(manifest.contains("FULCRUM_VALKEY_ENDPOINT: \"fulcrum-valkey:6379\""));
        assertTrue(manifest.contains("fieldPath: metadata.name"));
        assertTrue(manifest.contains("fieldPath: spec.nodeName"));
        assertTrue(manifest.contains("value: \"pool-velocity\""));
        assertTrue(manifest.contains("kind: Service"));
        assertTrue(manifest.contains("name: fulcrum-velocity-l4"));
        assertTrue(manifest.contains("type: LoadBalancer"));
        assertTrue(manifest.contains("targetPort: minecraft"));
    }

    @Test
    void velocityReadmeDocumentsPhase3DeployPath() throws IOException {
        String readme = resource("fulcrum/kubernetes/velocity/README.md");

        assertTrue(readme.contains(":distribution:service-launcher:paperAgonesPhase3Deploy"));
        assertTrue(readme.contains(".\\gradlew.bat clusterE2e"));
        assertTrue(readme.contains("lobbyClusterE2eVerify"));
        assertTrue(readme.contains("Minecraft status handshake"));
        assertTrue(readme.contains("fulcrum:lobby_probe"));
        assertTrue(readme.contains("Paper play state"));
        assertTrue(readme.contains("same Paper Instance"));
        assertTrue(readme.contains("same Paper Instance\nand Session"));
        assertTrue(readme.contains("a third login is denied to trigger controller-owned allocation"));
        assertTrue(readme.contains("a fourth bot must then reach a different Paper Instance and Session"));
        assertTrue(readme.contains("login gate denies the seeded punished bot"));
        assertTrue(readme.contains("-Pfulcrum.lobbyEndpointHost=<host-or-ip>"));
        assertTrue(readme.contains("-Pfulcrum.lobbyEndpointPort=25565"));
        assertTrue(readme.contains("-Pfulcrum.lobbyVelocityService=fulcrum-velocity-l4"));
        assertTrue(readme.contains("Agones Fleet reports"));
        assertTrue(readme.contains("-Pfulcrum.lobbyAgonesFleetName=fulcrum-lobby-paper"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyAgonesFleetState=true"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyAgonesAllocatedReplicas=2"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyScaleOut=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyTargetCapacity=1"));
        assertTrue(readme.contains("-Pfulcrum.lobbyHardCapacity=2"));
        assertTrue(readme.contains("-Pfulcrum.minecraftProtocolVersion=0"));
        assertTrue(readme.contains("-Pfulcrum.lobbyLoginUsername=FulcrumBotOne"));
        assertTrue(readme.contains("-Pfulcrum.secondLobbyLoginUsername=FulcrumBotTwo"));
        assertTrue(readme.contains("-Pfulcrum.scaleOutTriggerLobbyLoginUsername=FulcrumBotThree"));
        assertTrue(readme.contains("-Pfulcrum.scaleOutTriggerDeniedLobbyLoginReasonContains=No lobby route is currently available"));
        assertTrue(readme.contains("-Pfulcrum.scaleOutLobbyLoginUsername=FulcrumBotFour"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbySpawnBlock=bedrock"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbySpawnWorld=world"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyBedrockBlockX=0"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyBedrockBlockY=64"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyBedrockBlockZ=0"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyPlayerX=0.5"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyPlayerY=65.0"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyPlayerZ=0.5"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyPlayerYaw=0.0"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyPlayerPitch=0.0"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyDisplayName=Fulcrum Bot One"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyRankLabel=Admin"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyDecoratedChatContains=[Admin] Fulcrum Bot One: fulcrum-proof-chat"));
        assertTrue(readme.contains("-Pfulcrum.expectedSecondLobbyDisplayName=Fulcrum Bot Two"));
        assertTrue(readme.contains("-Pfulcrum.expectedSecondLobbyRankLabel=Admin"));
        assertTrue(readme.contains("-Pfulcrum.expectedSecondLobbyDecoratedChatContains=[Admin] Fulcrum Bot Two: fulcrum-proof-chat"));
        assertTrue(readme.contains("-Pfulcrum.expectedScaleOutLobbyDisplayName=Fulcrum Bot Four"));
        assertTrue(readme.contains("-Pfulcrum.expectedScaleOutLobbyRankLabel=Admin"));
        assertTrue(readme.contains("-Pfulcrum.expectedScaleOutLobbyDecoratedChatContains=[Admin] Fulcrum Bot Four: fulcrum-proof-chat"));
        assertTrue(readme.contains("-Pfulcrum.lobbyScaleOutTimeout=PT60S"));
        assertTrue(readme.contains("-Pfulcrum.deniedLobbyLoginUsername=FulcrumBannedOne"));
        assertTrue(readme.contains("-Pfulcrum.deniedLobbyLoginReasonContains=Banned from the lobby"));
        assertTrue(readme.contains("ghcr.io/sh-harold/fulcrum-velocity-proxy:dev"));
        assertTrue(readme.contains("-Pfulcrum.velocityProxyImage=<image-ref>"));
        assertTrue(readme.contains("velocityL4RenderManifests"));
        assertTrue(readme.contains("velocityL4Apply"));
        assertTrue(readme.contains("LoadBalancer"));
        assertTrue(readme.contains("fulcrum-velocity-l4"));
        assertTrue(readme.contains("FULCRUM_VELOCITY_ROUTE_BRIDGE_URL"));
        assertTrue(readme.contains("FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC"));
        assertTrue(readme.contains("FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_PRESENCE_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL"));
        assertTrue(readme.contains("FULCRUM_LOBBY_RESOLVED_MANIFEST_ID"));
        assertTrue(readme.contains("FULCRUM_VALKEY_ENDPOINT"));
        assertTrue(readme.contains("velocityL4RenderManifests"));
        assertTrue(readme.contains("the default cluster E2E values are `1` and `2`"));
        assertTrue(readme.contains("ctrl.state.shared-shard-allocation"));
        assertTrue(readme.contains("http://127.0.0.1:18081/routes"));
        assertTrue(readme.contains("http://127.0.0.1:18082/login-gate"));
    }

    private static String resource(String name) throws IOException {
        try (var stream = VelocityDeploymentManifestTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
