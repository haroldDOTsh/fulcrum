package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(manifest.contains("FULCRUM_QUEUE_ROSTER_COMMAND_TOPIC: \"ctrl.cmd.queue-roster\""));
        assertTrue(manifest.contains("FULCRUM_PRESENCE_COMMAND_TOPIC: \"cmd.presence\""));
        assertTrue(manifest.contains("FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC: \"ctrl.cmd.shared-shard-placement\""));
        assertTrue(manifest.contains("FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC: \"ctrl.cmd.route-attempt\""));
        assertTrue(manifest.contains("FULCRUM_LIFECYCLE_TRACE_COMMAND_TOPIC: \"ctrl.cmd.lifecycle-trace\""));
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
    void lobbyVelocityManifestDoesNotExposeCanonicalStoreCredentialsToVelocityHosts() throws IOException {
        String manifest = resource("fulcrum/kubernetes/velocity/lobby-velocity.yaml");

        assertTrue(manifest.contains("FULCRUM_VALKEY_ENDPOINT: \"fulcrum-valkey:6379\""));
        assertFalse(manifest.contains("FULCRUM_POSTGRES_"));
        assertFalse(manifest.contains("POSTGRES_PASSWORD"));
        assertFalse(manifest.contains("FULCRUM_CASSANDRA_"));
        assertFalse(manifest.contains("CASSANDRA_USERNAME"));
        assertFalse(manifest.contains("CASSANDRA_PASSWORD"));
        assertFalse(manifest.contains("FULCRUM_OBJECT_STORE_"));
        assertFalse(manifest.contains("fulcrum-object-store-credentials"));
    }

    @Test
    void velocityContainerConfigRegistersNonRoutedPlaceholder() throws IOException {
        String config = resource("fulcrum/container/velocity-proxy/velocity.toml");

        assertTrue(config.contains("config-version = \"2.8\""));
        assertTrue(config.contains("[advanced]"));
        assertTrue(config.contains("login-ratelimit = 0"));
        assertTrue(config.contains("[servers]"));
        assertTrue(config.contains("lobby = \"127.0.0.1:25566\""));
        assertTrue(config.contains("try = []"));
    }

    @Test
    void velocityReadmeDocumentsPhase3DeployPath() throws IOException {
        String readme = resource("fulcrum/kubernetes/velocity/README.md");

        assertTrue(readme.contains(":distribution:service-launcher:paperAgonesPhase3Deploy"));
        assertTrue(readme.contains(".\\gradlew.bat clusterE2e"));
        assertTrue(readme.contains(".\\gradlew.bat clusterK3sE2e"));
        assertTrue(readme.contains(".\\gradlew.bat clusterExistingE2e"));
        assertTrue(readme.contains("generated k3d cluster by default"));
        assertTrue(readme.contains("locally built"));
        assertTrue(readme.contains("Fulcrum images into that cluster"));
        assertTrue(readme.contains("existing-cluster profile remains available"));
        assertTrue(readme.contains("velocityL4WaitForReady"));
        assertTrue(readme.contains("lobbyClusterE2eVerify"));
        assertTrue(readme.contains("headless Minecraft status"));
        assertTrue(readme.contains("fulcrum:lobby_probe"));
        assertTrue(readme.contains("Paper play state"));
        assertTrue(readme.contains("Velocity route id"));
        assertTrue(readme.contains("deterministic Velocity login route"));
        assertTrue(readme.contains("same Paper Instance"));
        assertTrue(readme.contains("same Paper Instance, Session, and Slot"));
        assertTrue(readme.contains("a third login is denied to"));
        assertTrue(readme.contains("trigger controller-owned allocation"));
        assertTrue(readme.contains("a fourth bot must then reach"));
        assertTrue(readme.contains("different Paper Instance, Session, and Slot"));
        assertTrue(readme.contains("login gate denies the seeded punished bot"));
        assertTrue(readme.contains("-Pfulcrum.lobbyEndpointHost=<host-or-ip>"));
        assertTrue(readme.contains("-Pfulcrum.lobbyEndpointPort=25565"));
        assertTrue(readme.contains("-Pfulcrum.lobbyVelocityService=fulcrum-velocity-l4"));
        assertTrue(readme.contains("-Pfulcrum.kubeconfig=<generated-kubeconfig-path>"));
        assertTrue(readme.contains("generated kubeconfig is used for the verifier's `kubectl` reads"));
        assertTrue(readme.contains("Agones Fleet reports"));
        assertTrue(readme.contains("Paper Instance is an `Allocated` Agones GameServer"));
        assertTrue(readme.contains("matching"));
        assertTrue(readme.contains("Pool, Session, Slot, ResolvedManifest, and trace metadata"));
        assertTrue(readme.contains("`cmd.route`"));
        assertTrue(readme.contains("traced `open-route` and `acknowledge-route`"));
        assertTrue(readme.contains("same Subject, Route, Session"));
        assertTrue(readme.contains("fresh route expiry"));
        assertTrue(readme.contains("rejecting any fresh command for the seeded denied Subject"));
        assertTrue(readme.contains("`ctrl.cmd.queue-roster`, `cmd.presence`,"));
        assertTrue(readme.contains("`ctrl.cmd.lifecycle-trace`"));
        assertTrue(readme.contains("typed queue submit/form, Presence claim"));
        assertTrue(readme.contains("lifecycle trace commands"));
        assertTrue(readme.contains("shared-shard placement"));
        assertTrue(readme.contains("route-attempt request"));
        assertTrue(readme.contains("expected cluster principal, fencing, revisions"));
        assertTrue(readme.contains("allocation candidate correlation"));
        assertTrue(readme.contains("denied Subject must be absent from"));
        assertTrue(readme.contains("login-routing command logs"));
        assertTrue(readme.contains("`ctrl.state.queue-roster`"));
        assertTrue(readme.contains("queue intent to be rostered"));
        assertTrue(readme.contains("`ctrl.state.lifecycle-trace`"));
        assertTrue(readme.contains("queue, roster, allocation, route-attempt, Paper host attach"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyQueueRosterState=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyQueueRosterStateTopic=ctrl.state.queue-roster"));
        assertTrue(readme.contains("`host.velocity.routes` and `host.paper.commands`"));
        assertTrue(readme.contains("addressed `proxy.route` and `host.route.prepare`"));
        assertTrue(readme.contains("route attempt, Subject, Route, Session"));
        assertTrue(readme.contains("rejecting any downstream host-route command"));
        assertTrue(readme.contains("seeded denied Subject is absent from controller route-attempt"));
        assertTrue(readme.contains("`host.observation`"));
        assertTrue(readme.contains("raw `host.session-attached` observation"));
        assertTrue(readme.contains("Subject, Route, Session, Paper Instance, Pool"));
        assertTrue(readme.contains("rejecting any fresh host session attachment"));
        assertTrue(readme.contains("`ctrl.cmd.shared-shard-allocation`"));
        assertTrue(readme.contains("typed shared-shard allocation request"));
        assertTrue(readme.contains("`ctrl.state.shared-shard-allocation`"));
        assertTrue(readme.contains("controller-owned shared-shard allocation state"));
        assertTrue(readme.contains("Experience, Pool, Slot, Paper Instance, and ResolvedManifest"));
        assertTrue(readme.contains("Projection consistency verification fails the cluster gate"));
        assertTrue(readme.contains("Kafka state, Cassandra hot projections, PostgreSQL authority records"));
        assertTrue(readme.contains("LIVE Presence authority state"));
        assertTrue(readme.contains("deterministic Velocity login Presence"));
        assertTrue(readme.contains("matching Session and Route"));
        assertTrue(readme.contains("rejects any fresh LIVE Presence for that denied Subject"));
        assertTrue(readme.contains("`state.standard.player-profile`, `state.standard.rank`, and"));
        assertTrue(readme.contains("materialized standard capability state"));
        assertTrue(readme.contains("active punishment state matching the login-gate"));
        assertTrue(readme.contains("ACTIVE Session authority state"));
        assertTrue(readme.contains("fresh lease"));
        assertTrue(readme.contains("matching Slot, Paper Instance, and ResolvedManifest"));
        assertTrue(readme.contains("traced `open-session` and"));
        assertTrue(readme.contains("`activate-session` commands"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbySessionAuthorityCommandLog=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbySessionAuthorityCommandTopic=cmd.session"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyRouteAuthorityCommandLog=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyRouteAuthorityCommandTopic=cmd.route"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyLoginRoutingCommandLog=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPresenceAuthorityCommandTopic=cmd.presence"));
        assertTrue(readme.contains("-Pfulcrum.lobbySharedShardPlacementCommandTopic=ctrl.cmd.shared-shard-placement"));
        assertTrue(readme.contains("-Pfulcrum.lobbyRouteAttemptCommandTopic=ctrl.cmd.route-attempt"));
        assertTrue(readme.contains("-Pfulcrum.lobbyLifecycleTraceCommandTopic=ctrl.cmd.lifecycle-trace"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyLifecycleTraceState=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyLifecycleTraceStateTopic=ctrl.state.lifecycle-trace"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyHostRouteCommandLogs=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyProxyRouteCommandTopic=host.velocity.routes"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPaperHostCommandTopic=host.paper.commands"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbySharedShardAllocationCommandLog=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbySharedShardAllocationCommandTopic=ctrl.cmd.shared-shard-allocation"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbySharedShardAllocationState=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbySharedShardAllocationStateTopic=ctrl.state.shared-shard-allocation"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyProjectionConsistency=true"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyTraceCorrelation=true"));
        assertTrue(readme.contains("Trace correlation verification fails"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyHostObservationLog=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyHostObservationTopic=host.observation"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyPresenceAuthorityState=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPresenceAuthorityStateTopic=state.presence"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyStandardCapabilityState=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPlayerProfileStateTopic=state.standard.player-profile"));
        assertTrue(readme.contains("-Pfulcrum.lobbyRankStateTopic=state.standard.rank"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPunishmentStateTopic=state.standard.punishment"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyStandardCapabilityCommandLog=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPlayerProfileCommandTopic=cmd.standard.player-profile"));
        assertTrue(readme.contains("-Pfulcrum.lobbyRankCommandTopic=cmd.standard.rank"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPunishmentCommandTopic=cmd.standard.punishment"));
        assertTrue(readme.contains("-Pfulcrum.lobbyAgonesFleetName=fulcrum-lobby-paper"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyAgonesFleetState=true"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyAgonesAllocatedReplicas=2"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbySessionAuthorityState=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbySessionAuthorityStateTopic=state.session"));
        assertTrue(readme.contains("-Pfulcrum.verifyLobbyScaleOut=true"));
        assertTrue(readme.contains("-Pfulcrum.lobbyTargetCapacity=1"));
        assertTrue(readme.contains("-Pfulcrum.lobbyHardCapacity=2"));
        assertTrue(readme.contains("-Pfulcrum.minecraftProtocolVersion=775"));
        assertTrue(readme.contains("-Pfulcrum.lobbyLoginUsername=FulcrumBotOne"));
        assertTrue(readme.contains("-Pfulcrum.secondLobbyLoginUsername=FulcrumBotTwo"));
        assertTrue(readme.contains("-Pfulcrum.scaleOutTriggerLobbyLoginUsername=FulcrumBotThree"));
        assertTrue(readme.contains("-Pfulcrum.scaleOutTriggerDeniedLobbyLoginReasonContains=No lobby route is currently available"));
        assertTrue(readme.contains("-Pfulcrum.scaleOutLobbyLoginUsername=FulcrumBotFour"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyResolvedManifestId=manifest-lobby-bedrock-v1"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyExperienceId=experience-lobby"));
        assertTrue(readme.contains("-Pfulcrum.expectedLobbyPoolId=pool-lobby"));
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
        assertTrue(readme.contains("-Pfulcrum.lobbyEndpointReadyTimeout=PT120S"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPresenceAuthorityStateTimeout=PT60S"));
        assertTrue(readme.contains("-Pfulcrum.lobbyPresenceAuthorityStateFreshnessSkew=PT5S"));
        assertTrue(readme.contains("-Pfulcrum.lobbyStandardCapabilityStateTimeout=PT60S"));
        assertTrue(readme.contains("ghcr.io/sh-harold/fulcrum-velocity-proxy:dev"));
        assertTrue(readme.contains("-Pfulcrum.velocityProxyImage=<image-ref>"));
        assertTrue(readme.contains("velocityL4RenderManifests"));
        assertTrue(readme.contains("velocityL4Apply"));
        assertTrue(readme.contains("LoadBalancer"));
        assertTrue(readme.contains("fulcrum-velocity-l4"));
        assertTrue(readme.contains("FULCRUM_VELOCITY_ROUTE_BRIDGE_URL"));
        assertTrue(readme.contains("FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC"));
        assertTrue(readme.contains("FULCRUM_QUEUE_ROSTER_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_LIFECYCLE_TRACE_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_PRESENCE_COMMAND_TOPIC"));
        assertTrue(readme.contains("FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL"));
        assertTrue(readme.contains("FULCRUM_LOBBY_RESOLVED_MANIFEST_ID"));
        assertTrue(readme.contains("FULCRUM_VALKEY_ENDPOINT"));
        assertTrue(readme.contains("velocityL4RenderManifests"));
        assertTrue(readme.contains("the default cluster E2E values are `1` and `2`"));
        assertTrue(readme.contains("proves the typed allocation"));
        assertTrue(readme.contains("request before accepting the materialized endpoint state"));
        assertTrue(readme.contains("ctrl.state.shared-shard-allocation"));
        assertTrue(readme.contains("http://127.0.0.1:18081/routes"));
        assertTrue(readme.contains("http://127.0.0.1:18082/login-gate"));
        assertTrue(readme.contains("Velocity host pods must not receive PostgreSQL, Cassandra, or object-store"));
        assertTrue(readme.contains("canonical store access behind the"));
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
