package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.control.lifecycle.ControlLifecycleNames;
import sh.harold.fulcrum.control.lifecycle.LifecyclePhase;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlCommand;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceControlRecord;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceEntry;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceId;
import sh.harold.fulcrum.control.lifecycle.LifecycleTraceRecord;
import sh.harold.fulcrum.control.lifecycle.RecordLifecycleObservation;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.control.route.RouteAttemptLifecycleStatus;
import sh.harold.fulcrum.control.route.RouteAttemptSnapshot;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.QueueIntentSnapshot;
import sh.harold.fulcrum.control.queue.QueueIntentStatus;
import sh.harold.fulcrum.control.queue.QueueRosterControlRecord;
import sh.harold.fulcrum.control.queue.QueueRosterState;
import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.control.queue.RosterIntentSnapshot;
import sh.harold.fulcrum.control.queue.RosterIntentStatus;
import sh.harold.fulcrum.data.route.RouteLifecycleStatus;
import sh.harold.fulcrum.data.route.RouteSnapshot;
import sh.harold.fulcrum.data.route.RouteState;
import sh.harold.fulcrum.data.presence.PresenceLifecycleStatus;
import sh.harold.fulcrum.data.presence.PresenceAuthority;
import sh.harold.fulcrum.data.presence.PresenceOwnerToken;
import sh.harold.fulcrum.data.presence.PresenceSnapshot;
import sh.harold.fulcrum.data.presence.PresenceState;
import sh.harold.fulcrum.data.route.RouteAuthority;
import sh.harold.fulcrum.data.session.SessionLifecycleStatus;
import sh.harold.fulcrum.data.session.SessionAuthority;
import sh.harold.fulcrum.data.session.SessionOwnerToken;
import sh.harold.fulcrum.data.session.SessionSnapshot;
import sh.harold.fulcrum.data.session.SessionState;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.LoginAttemptResult;
import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.MinecraftStatusSnapshot;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.api.HostSessionAttachment;
import sh.harold.fulcrum.host.paper.PaperLobbyProofMessage;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.StatsContracts;
import sh.harold.fulcrum.standard.economy.EconomyAuthority;
import sh.harold.fulcrum.standard.economy.EconomyBalanceSnapshot;
import sh.harold.fulcrum.standard.economy.EconomyState;
import sh.harold.fulcrum.standard.economy.PostLedgerEntry;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.PlayerProfileSnapshot;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.punishment.ActivePunishmentSnapshot;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.punishment.PunishmentState;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.RankAuthority;
import sh.harold.fulcrum.standard.rank.RankState;
import sh.harold.fulcrum.standard.stats.RecordStatDelta;
import sh.harold.fulcrum.standard.stats.StatsCounterSnapshot;
import sh.harold.fulcrum.standard.stats.StatsState;
import sh.harold.fulcrum.standard.stats.StatsAuthority;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftStatusClientTest {
    @Test
    void statusClientSpeaksMinecraftStatusProtocol() throws Exception {
        try (FakeMinecraftStatusServer server = FakeMinecraftStatusServer.start()) {
            MinecraftStatusSnapshot status = new MinecraftStatusClient().status(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    0,
                    Duration.ofSeconds(2));

            assertEquals("Fulcrum Test Velocity", status.versionName());
            assertEquals(767, status.protocolVersion());
            assertEquals(2, status.onlinePlayers());
            assertEquals(100, status.maxPlayers());
            assertTrue(status.rawJson().contains("Fulcrum lobby"));
        }
    }

    @Test
    void loginProbeAcceptsOfflineModeLoginSuccess() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOGIN_ACCEPTED)) {
            LoginAttemptResult result = new MinecraftStatusClient().login(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2));

            assertTrue(result.accepted());
            assertEquals("FulcrumBotOne", result.username());
        }
    }

    @Test
    void loginProbeReturnsDisconnectReasonForDeniedLogin() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOGIN_DENIED)) {
            LoginAttemptResult result = new MinecraftStatusClient().login(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "BannedFulcrumBot",
                    Duration.ofSeconds(2));

            assertFalse(result.accepted());
            assertTrue(result.denialReason().orElseThrow().contains("Banned by Fulcrum"));
        }
    }

    @Test
    void lobbyProofProbeCompletesConfigurationAndReadsPaperPluginMessage() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOBBY_PROOF_ACCEPTED)) {
            PaperLobbyProofMessage proof = new MinecraftStatusClient().lobbyProof(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2));

            assertEquals(new InstanceId("paper-instance-lobby-one"), proof.instanceId());
            assertEquals(new SessionId("session-lobby-shared"), proof.sessionId());
            assertEquals(
                    routeId(LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value()),
                    proof.routeId());
            assertEquals(new SlotId("slot-lobby-shared"), proof.slotId());
            assertEquals(new ResolvedManifestId("manifest-lobby-bedrock-v1"), proof.resolvedManifestId());
            assertEquals("trace-paper-session-lobby-shared", proof.traceId());
            assertEquals("world", proof.spawnWorld());
            assertEquals(0, proof.bedrockBlockX());
            assertEquals(64, proof.bedrockBlockY());
            assertEquals(0, proof.bedrockBlockZ());
            assertEquals(0.5D, proof.playerX());
            assertEquals(65.0D, proof.playerY());
            assertEquals(0.5D, proof.playerZ());
            assertEquals(0.0F, proof.playerYaw());
            assertEquals(0.0F, proof.playerPitch());
            assertEquals("Fulcrum Bot One", proof.displayName());
            assertEquals("Admin", proof.rankLabel().orElseThrow());
            assertEquals("[Admin] Fulcrum Bot One: fulcrum-proof-chat", proof.decoratedChat());
        }
    }

    @Test
    void lobbyProofProbeIgnoresMarkerOnWrongPluginChannel() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOBBY_PROOF_WRONG_CHANNEL_THEN_ACCEPTED)) {
            PaperLobbyProofMessage proof = new MinecraftStatusClient().lobbyProof(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2));

            assertEquals(new InstanceId("paper-instance-lobby-one"), proof.instanceId());
            assertEquals(new SessionId("session-lobby-shared"), proof.sessionId());
        }
    }

    @Test
    void lobbyProofProbeIgnoresMarkerOutsideCustomPayloadPacket() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOBBY_PROOF_WRONG_PACKET_THEN_ACCEPTED)) {
            PaperLobbyProofMessage proof = new MinecraftStatusClient().lobbyProof(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2));

            assertEquals(new InstanceId("paper-instance-lobby-one"), proof.instanceId());
            assertEquals(new SessionId("session-lobby-shared"), proof.sessionId());
        }
    }

    @Test
    void lobbyProofProbeRejectsProofBeforePlayState() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOBBY_PROOF_BEFORE_PLAY)) {
            IOException exception = assertThrows(IOException.class, () -> new MinecraftStatusClient().lobbyProof(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2)));

            assertTrue(exception.getMessage().contains("before Minecraft client reached play state"));
        }
    }

    @Test
    void verifierAcceptsExplicitEndpointForClusterGate() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_ACCEPTED)) {
            LobbyClusterE2eVerifier.main(new String[]{
                    "--endpoint-host=127.0.0.1",
                    "--endpoint-port=" + server.port(),
                    "--timeout=PT2S"
            });
        }
    }

    @Test
    void verifierWaitsForEndpointStatusBeforeLoginAssertions() throws Exception {
        int port = unusedLocalPort();
        var executor = Executors.newSingleThreadExecutor();
        Future<Void> verifier = executor.submit(() -> {
            LobbyClusterE2eVerifier.main(new String[]{
                    "--endpoint-host=127.0.0.1",
                    "--endpoint-port=" + port,
                    "--endpoint-ready-timeout=PT3S",
                    "--timeout=PT1S"
            });
            return null;
        });
        executor.shutdown();

        Thread.sleep(250);
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.startOnPort(
                port,
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_ACCEPTED)) {
            verifier.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void verifierDefaultsAgonesFleetStateCheckToKubernetesResolvedRuns() {
        LobbyClusterE2eVerifier.VerificationConfig kubernetesResolved =
                LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{});
        LobbyClusterE2eVerifier.VerificationConfig explicitEndpoint =
                LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{
                        "--endpoint-host=127.0.0.1"
                });
        LobbyClusterE2eVerifier.VerificationConfig forcedExplicitEndpoint =
                LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{
                        "--endpoint-host=127.0.0.1",
                        "--verify-agones-fleet-state=true",
                        "--verify-route-attempt-state=true",
                        "--verify-login-routing-command-log=true",
                        "--verify-host-observation-log=true",
                        "--verify-presence-authority-state=true",
                        "--verify-standard-capability-state=true",
                        "--verify-standard-capability-command-log=true",
                        "--verify-reward-state=true",
                        "--verify-reward-command-log=true",
                        "--verify-cassandra-hot-projections=true",
                        "--verify-postgres-authority-records=true",
                        "--verify-valkey-cache=true",
                        "--verify-object-store-artifact=true",
                        "--verify-session-authority-state=true",
                        "--verify-session-authority-command-log=true",
                        "--verify-shared-shard-allocation-command-log=true",
                        "--verify-shared-shard-allocation-state=true",
                        "--verify-lifecycle-trace-state=true",
                        "--agones-fleet-name=test-lobby-fleet",
                        "--expected-agones-allocated-replicas=2",
                        "--route-attempt-state-topic=test.route-state",
                        "--presence-authority-command-topic=test.presence-command",
                        "--shared-shard-placement-command-topic=test.shared-shard-placement-command",
                        "--route-attempt-command-topic=test.route-attempt-command",
                        "--lifecycle-trace-command-topic=test.lifecycle-command",
                        "--lifecycle-trace-state-topic=test.lifecycle-state",
                        "--host-observation-topic=test.host-observation",
                        "--presence-authority-state-topic=test.presence-state",
                        "--player-profile-state-topic=test.profile-state",
                        "--rank-state-topic=test.rank-state",
                        "--punishment-state-topic=test.punishment-state",
                        "--player-profile-command-topic=test.profile-command",
                        "--rank-command-topic=test.rank-command",
                        "--punishment-command-topic=test.punishment-command",
                        "--economy-state-topic=test.economy-state",
                        "--stats-state-topic=test.stats-state",
                        "--economy-command-topic=test.economy-command",
                        "--stats-command-topic=test.stats-command",
                        "--expected-reward-currency-key=tokens",
                        "--expected-reward-amount-minor-units=500",
                        "--expected-reward-stat-key=round-wins",
                        "--expected-reward-command-delivery-copies=2",
                        "--cassandra-pod-name=test-cassandra-0",
                        "--cassandra-container-name=test-cassandra",
                        "--cassandra-cqlsh-path=/test/cqlsh",
                        "--postgres-pod-name=test-postgres-0",
                        "--postgres-container-name=test-postgres",
                        "--postgres-psql-path=/test/psql",
                        "--postgres-database=test-db",
                        "--postgres-username=test-user",
                        "--valkey-resource-name=deployment/test-valkey",
                        "--valkey-container-name=test-valkey",
                        "--valkey-cli-path=/test/valkey-cli",
                        "--verify-projection-consistency=true",
                        "--verify-trace-correlation=true",
                        "--object-store-resource-name=service/test-object-store",
                        "--object-store-port=19000",
                        "--object-store-region=test-region-1",
                        "--object-store-bucket=test-artifacts",
                        "--object-store-secret-name=test-object-store-secret",
                        "--object-store-access-key-secret-key=TEST_ACCESS_KEY",
                        "--object-store-secret-key-secret-key=TEST_SECRET_KEY",
                        "--expected-lobby-world-artifact-id=test-lobby-world",
                        "--expected-lobby-world-artifact-digest=" + "0".repeat(64),
                        "--expected-lobby-world-artifact-compatibility=test-world-v2",
                        "--session-authority-state-topic=test.session-state",
                        "--session-authority-command-topic=test.session-command",
                        "--shared-shard-allocation-command-topic=test.shared-shard-allocation-command",
                        "--shared-shard-allocation-state-topic=test.shared-shard-allocation",
                        "--verify-route-authority-state=true",
                        "--route-authority-state-topic=test.route-authority-state",
                        "--expected-lobby-experience-id=test-experience",
                        "--expected-lobby-pool-id=test-pool",
                        "--kafka-pod-name=test-kafka-0",
                        "--kafka-container-name=test-kafka",
                        "--kafka-bootstrap-server=localhost:19092",
                        "--kafka-console-consumer-path=/test/kafka-console-consumer.sh",
                        "--route-attempt-state-timeout=PT3S",
                        "--route-attempt-state-freshness-skew=PT2S",
                        "--presence-authority-state-timeout=PT6S",
                        "--presence-authority-state-freshness-skew=PT3S",
                        "--standard-capability-state-timeout=PT7S",
                        "--cassandra-hot-projection-timeout=PT8S",
                        "--postgres-authority-record-timeout=PT9S",
                        "--valkey-cache-timeout=PT10S",
                        "--object-store-artifact-timeout=PT11S",
                        "--session-authority-state-timeout=PT4S",
                        "--session-authority-state-freshness-skew=PT1S",
                        "--shared-shard-allocation-state-timeout=PT5S"
                });

        assertTrue(kubernetesResolved.verifyAgonesFleetState());
        assertTrue(kubernetesResolved.verifyRouteAttemptState());
        assertTrue(kubernetesResolved.verifyLoginRoutingCommandLog());
        assertTrue(kubernetesResolved.verifyHostObservationLog());
        assertTrue(kubernetesResolved.verifyPresenceAuthorityState());
        assertTrue(kubernetesResolved.verifyStandardCapabilityState());
        assertTrue(kubernetesResolved.verifyStandardCapabilityCommandLog());
        assertTrue(kubernetesResolved.verifyRewardState());
        assertTrue(kubernetesResolved.verifyRewardCommandLog());
        assertTrue(kubernetesResolved.verifyCassandraHotProjections());
        assertTrue(kubernetesResolved.verifyPostgresAuthorityRecords());
        assertTrue(kubernetesResolved.verifyValkeyCache());
        assertTrue(kubernetesResolved.verifyProjectionConsistency());
        assertTrue(kubernetesResolved.verifyTraceCorrelation());
        assertTrue(kubernetesResolved.verifyObjectStoreArtifact());
        assertTrue(kubernetesResolved.verifySessionAuthorityState());
        assertTrue(kubernetesResolved.verifySessionAuthorityCommandLog());
        assertTrue(kubernetesResolved.verifySharedShardAllocationCommandLog());
        assertTrue(kubernetesResolved.verifySharedShardAllocationState());
        assertTrue(kubernetesResolved.verifyRouteAuthorityState());
        assertTrue(kubernetesResolved.verifyLifecycleTraceState());
        assertFalse(explicitEndpoint.verifyAgonesFleetState());
        assertFalse(explicitEndpoint.verifyRouteAttemptState());
        assertFalse(explicitEndpoint.verifyLoginRoutingCommandLog());
        assertFalse(explicitEndpoint.verifyHostObservationLog());
        assertFalse(explicitEndpoint.verifyPresenceAuthorityState());
        assertFalse(explicitEndpoint.verifyStandardCapabilityState());
        assertFalse(explicitEndpoint.verifyStandardCapabilityCommandLog());
        assertFalse(explicitEndpoint.verifyRewardState());
        assertFalse(explicitEndpoint.verifyRewardCommandLog());
        assertFalse(explicitEndpoint.verifyCassandraHotProjections());
        assertFalse(explicitEndpoint.verifyPostgresAuthorityRecords());
        assertFalse(explicitEndpoint.verifyValkeyCache());
        assertFalse(explicitEndpoint.verifyProjectionConsistency());
        assertFalse(explicitEndpoint.verifyTraceCorrelation());
        assertFalse(explicitEndpoint.verifyObjectStoreArtifact());
        assertFalse(explicitEndpoint.verifySessionAuthorityState());
        assertFalse(explicitEndpoint.verifySessionAuthorityCommandLog());
        assertFalse(explicitEndpoint.verifySharedShardAllocationCommandLog());
        assertFalse(explicitEndpoint.verifySharedShardAllocationState());
        assertFalse(explicitEndpoint.verifyRouteAuthorityState());
        assertFalse(explicitEndpoint.verifyLifecycleTraceState());
        assertTrue(forcedExplicitEndpoint.verifyAgonesFleetState());
        assertTrue(forcedExplicitEndpoint.verifyRouteAttemptState());
        assertTrue(forcedExplicitEndpoint.verifyLoginRoutingCommandLog());
        assertTrue(forcedExplicitEndpoint.verifyHostObservationLog());
        assertTrue(forcedExplicitEndpoint.verifyPresenceAuthorityState());
        assertTrue(forcedExplicitEndpoint.verifyStandardCapabilityState());
        assertTrue(forcedExplicitEndpoint.verifyStandardCapabilityCommandLog());
        assertTrue(forcedExplicitEndpoint.verifyRewardState());
        assertTrue(forcedExplicitEndpoint.verifyRewardCommandLog());
        assertTrue(forcedExplicitEndpoint.verifyCassandraHotProjections());
        assertTrue(forcedExplicitEndpoint.verifyPostgresAuthorityRecords());
        assertTrue(forcedExplicitEndpoint.verifyValkeyCache());
        assertTrue(forcedExplicitEndpoint.verifyProjectionConsistency());
        assertTrue(forcedExplicitEndpoint.verifyTraceCorrelation());
        assertTrue(forcedExplicitEndpoint.verifyObjectStoreArtifact());
        assertTrue(forcedExplicitEndpoint.verifySessionAuthorityState());
        assertTrue(forcedExplicitEndpoint.verifySessionAuthorityCommandLog());
        assertTrue(forcedExplicitEndpoint.verifySharedShardAllocationCommandLog());
        assertTrue(forcedExplicitEndpoint.verifySharedShardAllocationState());
        assertTrue(forcedExplicitEndpoint.verifyRouteAuthorityState());
        assertTrue(forcedExplicitEndpoint.verifyLifecycleTraceState());
        assertEquals("test-lobby-fleet", forcedExplicitEndpoint.agonesFleetName());
        assertEquals(2, forcedExplicitEndpoint.expectedAgonesAllocatedReplicas());
        assertEquals("test.route-state", forcedExplicitEndpoint.routeAttemptStateTopic());
        assertEquals("test.presence-command", forcedExplicitEndpoint.presenceAuthorityCommandTopic());
        assertEquals("test.shared-shard-placement-command", forcedExplicitEndpoint.sharedShardPlacementCommandTopic());
        assertEquals("test.route-attempt-command", forcedExplicitEndpoint.routeAttemptCommandTopic());
        assertEquals("test.lifecycle-command", forcedExplicitEndpoint.lifecycleTraceCommandTopic());
        assertEquals("test.lifecycle-state", forcedExplicitEndpoint.lifecycleTraceStateTopic());
        assertEquals("test.host-observation", forcedExplicitEndpoint.hostObservationTopic());
        assertEquals("test.presence-state", forcedExplicitEndpoint.presenceAuthorityStateTopic());
        assertEquals("test.profile-state", forcedExplicitEndpoint.playerProfileStateTopic());
        assertEquals("test.rank-state", forcedExplicitEndpoint.rankStateTopic());
        assertEquals("test.punishment-state", forcedExplicitEndpoint.punishmentStateTopic());
        assertEquals("test.profile-command", forcedExplicitEndpoint.playerProfileCommandTopic());
        assertEquals("test.rank-command", forcedExplicitEndpoint.rankCommandTopic());
        assertEquals("test.punishment-command", forcedExplicitEndpoint.punishmentCommandTopic());
        assertEquals("test.economy-state", forcedExplicitEndpoint.economyStateTopic());
        assertEquals("test.stats-state", forcedExplicitEndpoint.statsStateTopic());
        assertEquals("test.economy-command", forcedExplicitEndpoint.economyCommandTopic());
        assertEquals("test.stats-command", forcedExplicitEndpoint.statsCommandTopic());
        assertEquals("tokens", forcedExplicitEndpoint.expectedRewardCurrencyKey());
        assertEquals(500, forcedExplicitEndpoint.expectedRewardAmountMinorUnits());
        assertEquals("round-wins", forcedExplicitEndpoint.expectedRewardStatKey());
        assertEquals(2, forcedExplicitEndpoint.expectedRewardCommandDeliveryCopies());
        assertEquals("test-cassandra-0", forcedExplicitEndpoint.cassandraPodName());
        assertEquals("test-cassandra", forcedExplicitEndpoint.cassandraContainerName());
        assertEquals("/test/cqlsh", forcedExplicitEndpoint.cassandraCqlshPath());
        assertEquals("test-postgres-0", forcedExplicitEndpoint.postgresPodName());
        assertEquals("test-postgres", forcedExplicitEndpoint.postgresContainerName());
        assertEquals("/test/psql", forcedExplicitEndpoint.postgresPsqlPath());
        assertEquals("test-db", forcedExplicitEndpoint.postgresDatabase());
        assertEquals("test-user", forcedExplicitEndpoint.postgresUsername());
        assertEquals("deployment/test-valkey", forcedExplicitEndpoint.valkeyResourceName());
        assertEquals("test-valkey", forcedExplicitEndpoint.valkeyContainerName());
        assertEquals("/test/valkey-cli", forcedExplicitEndpoint.valkeyCliPath());
        assertEquals("service/test-object-store", forcedExplicitEndpoint.objectStoreResourceName());
        assertEquals(19000, forcedExplicitEndpoint.objectStorePort());
        assertEquals("test-region-1", forcedExplicitEndpoint.objectStoreRegion());
        assertEquals("test-artifacts", forcedExplicitEndpoint.objectStoreBucket());
        assertEquals("test-object-store-secret", forcedExplicitEndpoint.objectStoreSecretName());
        assertEquals("TEST_ACCESS_KEY", forcedExplicitEndpoint.objectStoreAccessKeySecretKey());
        assertEquals("TEST_SECRET_KEY", forcedExplicitEndpoint.objectStoreSecretKeySecretKey());
        assertEquals(new ArtifactId("test-lobby-world"), forcedExplicitEndpoint.expectedLobbyWorldArtifactId());
        assertEquals("0".repeat(64), forcedExplicitEndpoint.expectedLobbyWorldArtifactDigest());
        assertEquals("test-world-v2", forcedExplicitEndpoint.expectedLobbyWorldArtifactCompatibility());
        assertEquals("test.session-state", forcedExplicitEndpoint.sessionAuthorityStateTopic());
        assertEquals("test.session-command", forcedExplicitEndpoint.sessionAuthorityCommandTopic());
        assertEquals("test.shared-shard-allocation-command",
                forcedExplicitEndpoint.sharedShardAllocationCommandTopic());
        assertEquals("test.shared-shard-allocation", forcedExplicitEndpoint.sharedShardAllocationStateTopic());
        assertEquals("test.route-authority-state", forcedExplicitEndpoint.routeAuthorityStateTopic());
        assertEquals(new ExperienceId("test-experience"), forcedExplicitEndpoint.expectedExperienceId());
        assertEquals(new PoolId("test-pool"), forcedExplicitEndpoint.expectedPoolId());
        assertEquals("test-kafka-0", forcedExplicitEndpoint.kafkaPodName());
        assertEquals("test-kafka", forcedExplicitEndpoint.kafkaContainerName());
        assertEquals("localhost:19092", forcedExplicitEndpoint.kafkaBootstrapServer());
        assertEquals("/test/kafka-console-consumer.sh", forcedExplicitEndpoint.kafkaConsoleConsumerPath());
        assertEquals(Duration.ofSeconds(3), forcedExplicitEndpoint.routeAttemptStateTimeout());
        assertEquals(Duration.ofSeconds(2), forcedExplicitEndpoint.routeAttemptStateFreshnessSkew());
        assertEquals(Duration.ofSeconds(6), forcedExplicitEndpoint.presenceAuthorityStateTimeout());
        assertEquals(Duration.ofSeconds(3), forcedExplicitEndpoint.presenceAuthorityStateFreshnessSkew());
        assertEquals(Duration.ofSeconds(7), forcedExplicitEndpoint.standardCapabilityStateTimeout());
        assertEquals(Duration.ofSeconds(8), forcedExplicitEndpoint.cassandraHotProjectionTimeout());
        assertEquals(Duration.ofSeconds(9), forcedExplicitEndpoint.postgresAuthorityRecordTimeout());
        assertEquals(Duration.ofSeconds(10), forcedExplicitEndpoint.valkeyCacheTimeout());
        assertEquals(Duration.ofSeconds(11), forcedExplicitEndpoint.objectStoreArtifactTimeout());
        assertEquals(Duration.ofSeconds(4), forcedExplicitEndpoint.sessionAuthorityStateTimeout());
        assertEquals(Duration.ofSeconds(1), forcedExplicitEndpoint.sessionAuthorityStateFreshnessSkew());
        assertEquals(Duration.ofSeconds(5), forcedExplicitEndpoint.sharedShardAllocationStateTimeout());
    }

    @Test
    void verifierAcceptsGeneratedKubeconfigAndPrefersItOverContext() {
        LobbyClusterE2eVerifier.VerificationConfig config =
                LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{
                        "--kube-context=docker-desktop",
                        "--kubeconfig=C:/tmp/fulcrum-k3s.yaml"
                });

        assertEquals(Optional.of("docker-desktop"), config.kubeContext());
        assertEquals(Optional.of("C:/tmp/fulcrum-k3s.yaml"), config.kubeconfig());
        assertEquals(
                List.of(
                        "kubectl",
                        "--kubeconfig",
                        "C:/tmp/fulcrum-k3s.yaml",
                        "-n",
                        "fulcrum-lobby",
                        "get",
                        "pods"),
                LobbyClusterE2eVerifier.kubectlCommand(config, "get", "pods"));
    }

    @Test
    void verifierMatchesControllerRouteAttemptAckStateToLobbyProof() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                Processed a total of 2 messages
                """.formatted(
                ControllerStateWireCodec.encodeRouteAttempt(routeAttemptRecord(
                        proof,
                        RouteAttemptLifecycleStatus.ISSUED_TO_HOST,
                        proof.slotId())),
                ControllerStateWireCodec.encodeRouteAttempt(routeAttemptRecord(
                        proof,
                        RouteAttemptLifecycleStatus.ACKED,
                        proof.slotId())));

        LobbyClusterE2eVerifier.RouteAttemptStateResult result =
                LobbyClusterE2eVerifier.verifyRouteAttemptStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof)));

        assertEquals(1, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsControllerRouteAttemptAckStateWithWrongSlot() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = ControllerStateWireCodec.encodeRouteAttempt(routeAttemptRecord(
                proof,
                RouteAttemptLifecycleStatus.ACKED,
                new SlotId("slot-unexpected-lobby")));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAttemptStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof))));

        assertTrue(exception.getMessage().contains("allocationSlotId"));
    }

    @Test
    void verifierRejectsStaleControllerRouteAttemptAckState() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = ControllerStateWireCodec.encodeRouteAttempt(routeAttemptRecord(
                proof,
                RouteAttemptLifecycleStatus.ACKED,
                proof.slotId()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAttemptStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof,
                                Instant.parse("2026-06-17T00:00:01Z")))));

        assertTrue(exception.getMessage().contains("updatedAt"));
    }

    @Test
    void verifierMatchesRouteAuthorityCommandLogToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage second = lobbyProof(
                "FulcrumBotTwo",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                %s
                %s
                Processed a total of 4 messages
                """.formatted(
                routeAuthorityCommand(
                        first,
                        RouteAuthorityWireCodec.OPEN_COMMAND,
                        first.traceId(),
                        Instant.parse("2026-06-17T00:05:00Z")),
                routeAuthorityCommand(
                        first,
                        RouteAuthorityWireCodec.ACKNOWLEDGE_COMMAND,
                        first.traceId(),
                        Instant.parse("2026-06-17T00:00:30Z")),
                routeAuthorityCommand(
                        second,
                        RouteAuthorityWireCodec.OPEN_COMMAND,
                        second.traceId(),
                        Instant.parse("2026-06-17T00:05:00Z")),
                routeAuthorityCommand(
                        second,
                        RouteAuthorityWireCodec.ACKNOWLEDGE_COMMAND,
                        second.traceId(),
                        Instant.parse("2026-06-17T00:00:31Z")));

        LobbyClusterE2eVerifier.RouteAuthorityCommandLogResult result =
                LobbyClusterE2eVerifier.verifyRouteAuthorityCommandLogOutput(
                        output,
                        List.of(
                                LobbyClusterE2eVerifier.RouteAuthorityCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first,
                                                Instant.parse("2026-06-17T00:00:01Z"))),
                                LobbyClusterE2eVerifier.RouteAuthorityCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "second accepted login",
                                                "FulcrumBotTwo",
                                                second,
                                                Instant.parse("2026-06-17T00:00:01Z")))),
                        List.of());

        assertEquals(2, result.matchedCount());
        assertEquals(4, result.recordsScanned());
    }

    @Test
    void verifierRejectsRouteAuthorityCommandLogWithWrongTrace() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAuthorityCommandLogOutput(
                        routeAuthorityCommand(
                                proof,
                                RouteAuthorityWireCodec.OPEN_COMMAND,
                                "trace-unexpected",
                                Instant.parse("2026-06-17T00:05:00Z")),
                        List.of(LobbyClusterE2eVerifier.RouteAuthorityCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof))),
                        List.of()));

        assertTrue(exception.getMessage().contains("traceId"));
    }

    @Test
    void verifierRejectsStaleRouteAuthorityCommandLogExpiry() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAuthorityCommandLogOutput(
                        routeAuthorityCommand(
                                proof,
                                RouteAuthorityWireCodec.OPEN_COMMAND,
                                proof.traceId(),
                                Instant.parse("2026-06-17T00:00:30Z")),
                        List.of(LobbyClusterE2eVerifier.RouteAuthorityCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof,
                                        Instant.parse("2026-06-17T00:05:01Z")))),
                        List.of()));

        assertTrue(exception.getMessage().contains("expiresAt"));
    }

    @Test
    void verifierRejectsRouteAuthorityCommandLogForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAuthorityCommandLogOutput(
                        routeAuthorityCommand(
                                deniedProof,
                                RouteAuthorityWireCodec.OPEN_COMMAND,
                                deniedProof.traceId(),
                                Instant.parse("2026-06-17T00:05:00Z")),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedRouteAuthorityCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne",
                                        Instant.parse("2026-06-16T23:59:59Z"))))));

        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
        assertTrue(exception.getMessage().contains(RouteAuthorityWireCodec.OPEN_COMMAND));
    }

    @Test
    void verifierMatchesRouteAuthorityStateToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage second = lobbyProof(
                "FulcrumBotTwo",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                Processed a total of 2 messages
                """.formatted(
                routeAuthorityState(first, first.sessionId(), first.instanceId()),
                routeAuthorityState(second, second.sessionId(), second.instanceId()));

        LobbyClusterE2eVerifier.RouteAuthorityStateResult result =
                LobbyClusterE2eVerifier.verifyRouteAuthorityStateOutput(
                        output,
                        List.of(
                                LobbyClusterE2eVerifier.RouteAuthorityStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first,
                                                Instant.parse("2026-06-17T00:00:01Z"))),
                                LobbyClusterE2eVerifier.RouteAuthorityStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "second accepted login",
                                                "FulcrumBotTwo",
                                                second,
                                                Instant.parse("2026-06-17T00:00:01Z")))),
                        List.of());

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsRouteAuthorityStateWithWrongSession() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAuthorityStateOutput(
                        routeAuthorityState(
                                proof,
                                new SessionId("session-unexpected-lobby"),
                                proof.instanceId()),
                        List.of(LobbyClusterE2eVerifier.RouteAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof,
                                        Instant.parse("2026-06-17T00:00:01Z")))),
                        List.of()));

        assertTrue(exception.getMessage().contains("targetSessionId"));
    }

    @Test
    void verifierRejectsRouteAuthorityStateForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAuthorityStateOutput(
                        routeAuthorityState(deniedProof, deniedProof.sessionId(), deniedProof.instanceId()),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedRouteAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne",
                                        Instant.parse("2026-06-17T00:00:01Z"))))));

        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
    }

    @Test
    void verifierMatchesVelocityLoginRoutingCommandLogsToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage second = lobbyProof(
                "FulcrumBotTwo",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        LobbyClusterE2eVerifier.LoginRoutingCommandLogResult result =
                LobbyClusterE2eVerifier.verifyLoginRoutingCommandLogOutput(
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        %s
                        %s
                        Processed a total of 4 messages
                        """.formatted(
                                queueRosterSubmitCommand(first),
                                queueRosterFormCommand(first),
                                queueRosterSubmitCommand(second),
                                queueRosterFormCommand(second)),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                presenceAuthorityCommand(first),
                                presenceAuthorityCommand(second)),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                sharedShardPlacementRequest(first),
                                sharedShardPlacementRequest(second)),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        %s
                        %s
                        %s
                        %s
                        Processed a total of 6 messages
                        """.formatted(
                                routeAttemptCommand(first, "ctrl.route.request-attempt", "request", 0,
                                        first.resolvedManifestId()),
                                routeAttemptCommand(first, "ctrl.route.issue-proxy", "issue-proxy", 1,
                                        first.resolvedManifestId()),
                                routeAttemptCommand(first, "ctrl.route.prepare-host", "prepare-host", 2,
                                        first.resolvedManifestId()),
                                routeAttemptCommand(second, "ctrl.route.request-attempt", "request", 0,
                                        second.resolvedManifestId()),
                                routeAttemptCommand(second, "ctrl.route.issue-proxy", "issue-proxy", 1,
                                        second.resolvedManifestId()),
                                routeAttemptCommand(second, "ctrl.route.prepare-host", "prepare-host", 2,
                                        second.resolvedManifestId())),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 8 messages
                        """.formatted(
                                lifecycleTraceCommands(first),
                                lifecycleTraceCommands(second)),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(
                                LobbyClusterE2eVerifier.LoginRoutingCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first,
                                                Instant.parse("2026-06-17T00:00:01Z"))),
                                LobbyClusterE2eVerifier.LoginRoutingCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "second accepted login",
                                                "FulcrumBotTwo",
                                                second,
                                                Instant.parse("2026-06-17T00:00:01Z")))),
                        List.of(LobbyClusterE2eVerifier.DeniedLoginRoutingCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne"))));

        assertEquals(2, result.matchedCount());
        assertEquals(4, result.queueRosterCommandsScanned());
        assertEquals(2, result.presenceCommandsScanned());
        assertEquals(2, result.placementRequestsScanned());
        assertEquals(6, result.routeAttemptCommandsScanned());
        assertEquals(8, result.lifecycleTraceCommandsScanned());
    }

    @Test
    void verifierRejectsVelocityLoginRoutingCommandLogWithWrongRouteAttemptManifest() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyLoginRoutingCommandLogOutput(
                        queueRosterSubmitCommand(proof) + queueRosterFormCommand(proof),
                        presenceAuthorityCommand(proof),
                        sharedShardPlacementRequest(proof),
                        routeAttemptCommand(
                                proof,
                                "ctrl.route.request-attempt",
                                "request",
                                0,
                                new ResolvedManifestId("manifest-unexpected")),
                        lifecycleTraceCommands(proof),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(LobbyClusterE2eVerifier.LoginRoutingCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof,
                                        Instant.EPOCH))),
                        List.of()));

        assertTrue(exception.getMessage().contains("targetResolvedManifestId"));
    }

    @Test
    void verifierRejectsVelocityLoginRoutingCommandLogForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyLoginRoutingCommandLogOutput(
                        queueRosterSubmitCommand(deniedProof),
                        presenceAuthorityCommand(deniedProof),
                        "",
                        "",
                        lifecycleTraceCommands(deniedProof),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedLoginRoutingCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne")))));

        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
    }

    @Test
    void verifierMatchesQueueRosterStateToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage second = lobbyProof(
                "FulcrumBotTwo",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        LobbyClusterE2eVerifier.QueueRosterStateResult result =
                LobbyClusterE2eVerifier.verifyQueueRosterStateOutput(
                        queueRosterState(first, second),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(
                                LobbyClusterE2eVerifier.LoginRoutingCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first)),
                                LobbyClusterE2eVerifier.LoginRoutingCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "second accepted login",
                                                "FulcrumBotTwo",
                                                second))),
                        List.of(LobbyClusterE2eVerifier.DeniedLoginRoutingCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne"))));

        assertEquals(2, result.matchedCount());
        assertEquals(1, result.recordsScanned());
    }

    @Test
    void verifierRejectsQueueRosterStateForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyQueueRosterStateOutput(
                        queueRosterState(deniedProof),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedLoginRoutingCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne")))));

        assertTrue(exception.getMessage().contains("queue-roster state"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
    }

    @Test
    void verifierMatchesLifecycleTraceStateToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage second = lobbyProof(
                "FulcrumBotTwo",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        LobbyClusterE2eVerifier.LifecycleTraceStateResult result =
                LobbyClusterE2eVerifier.verifyLifecycleTraceStateOutput(
                        lifecycleTraceState(first, second),
                        List.of(
                                LobbyClusterE2eVerifier.LoginRoutingCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first)),
                                LobbyClusterE2eVerifier.LoginRoutingCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "second accepted login",
                                                "FulcrumBotTwo",
                                                second))),
                        List.of(LobbyClusterE2eVerifier.DeniedLoginRoutingCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne"))));

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsLifecycleTraceStateForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyLifecycleTraceStateOutput(
                        lifecycleTraceState(deniedProof),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedLoginRoutingCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne")))));

        assertTrue(exception.getMessage().contains("lifecycle trace state"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
    }

    @Test
    void verifierMatchesAddressedHostRouteCommandsToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage second = lobbyProof(
                "FulcrumBotTwo",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        LobbyClusterE2eVerifier.HostRouteCommandLogResult result =
                LobbyClusterE2eVerifier.verifyHostRouteCommandLogsOutput(
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                velocityProxyRouteCommand(first),
                                velocityProxyRouteCommand(second)),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                paperHostRoutePrepareCommand(first),
                                paperHostRoutePrepareCommand(second)),
                        List.of(
                                LobbyClusterE2eVerifier.HostRouteCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first)),
                                LobbyClusterE2eVerifier.HostRouteCommandExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "second accepted login",
                                                "FulcrumBotTwo",
                                                second))),
                        List.of());

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.proxyRecordsScanned());
        assertEquals(2, result.paperRecordsScanned());
    }

    @Test
    void verifierRejectsAddressedHostRouteCommandWithWrongProxySubject() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyHostRouteCommandLogsOutput(
                        velocityProxyRouteCommand(
                                proof,
                                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo"),
                                proof.traceId()),
                        paperHostRoutePrepareCommand(proof),
                        List.of(LobbyClusterE2eVerifier.HostRouteCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof))),
                        List.of()));

        assertTrue(exception.getMessage().contains("subjectId"));
    }

    @Test
    void verifierRejectsAddressedHostRouteCommandWithWrongPaperManifest() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyHostRouteCommandLogsOutput(
                        velocityProxyRouteCommand(proof),
                        paperHostRoutePrepareCommand(
                                proof,
                                new ResolvedManifestId("manifest-unexpected"),
                                proof.traceId()),
                        List.of(LobbyClusterE2eVerifier.HostRouteCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof))),
                        List.of()));

        assertTrue(exception.getMessage().contains("resolvedManifestId"));
    }

    @Test
    void verifierRejectsAddressedHostRouteProxyCommandForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyHostRouteCommandLogsOutput(
                        velocityProxyRouteCommand(deniedProof),
                        """
                        Processed a total of 0 messages
                        """,
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedHostRouteCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne")))));

        assertTrue(exception.getMessage().contains("Velocity proxy route command log"));
        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
        assertTrue(exception.getMessage().contains("subjectId"));
    }

    @Test
    void verifierRejectsAddressedPaperHostRoutePrepareCommandForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyHostRouteCommandLogsOutput(
                        """
                        Processed a total of 0 messages
                        """,
                        paperHostRoutePrepareCommand(deniedProof),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedHostRouteCommandExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne")))));

        assertTrue(exception.getMessage().contains("Paper host route prepare command log"));
        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
        assertTrue(exception.getMessage().contains("routeAttemptId"));
    }

    @Test
    void verifierMatchesHostSessionAttachedObservationsToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage second = lobbyProof(
                "FulcrumBotTwo",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                Processed a total of 2 messages
                """.formatted(
                hostSessionAttachedObservation(
                        first,
                        paperAttachTraceId(first),
                        Instant.parse("2026-06-17T00:00:30Z")),
                hostSessionAttachedObservation(
                        second,
                        paperAttachTraceId(second),
                        Instant.parse("2026-06-17T00:00:31Z")));

        LobbyClusterE2eVerifier.HostObservationLogResult result =
                LobbyClusterE2eVerifier.verifyHostObservationLogOutput(
                        output,
                        List.of(
                                LobbyClusterE2eVerifier.HostObservationExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first),
                                        new PoolId("pool-lobby"),
                                        Instant.parse("2026-06-17T00:00:01Z")),
                                LobbyClusterE2eVerifier.HostObservationExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "second accepted login",
                                                "FulcrumBotTwo",
                                                second),
                                        new PoolId("pool-lobby"),
                                        Instant.parse("2026-06-17T00:00:01Z"))),
                        List.of());

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsHostSessionAttachedObservationWithWrongTrace() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyHostObservationLogOutput(
                        hostSessionAttachedObservation(
                                proof,
                                "trace-unexpected",
                                Instant.parse("2026-06-17T00:00:30Z")),
                        List.of(LobbyClusterE2eVerifier.HostObservationExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                new PoolId("pool-lobby"),
                                Instant.EPOCH)),
                        List.of()));

        assertTrue(exception.getMessage().contains("traceId"));
    }

    @Test
    void verifierRejectsStaleHostSessionAttachedObservation() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyHostObservationLogOutput(
                        hostSessionAttachedObservation(
                                proof,
                                paperAttachTraceId(proof),
                                Instant.parse("2026-06-17T00:00:30Z")),
                        List.of(LobbyClusterE2eVerifier.HostObservationExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                new PoolId("pool-lobby"),
                                Instant.parse("2026-06-17T00:05:01Z"))),
                        List.of()));

        assertTrue(exception.getMessage().contains("observedAt"));
    }

    @Test
    void verifierRejectsHostSessionAttachedObservationForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyHostObservationLogOutput(
                        hostSessionAttachedObservation(
                                deniedProof,
                                paperAttachTraceId(deniedProof),
                                Instant.parse("2026-06-17T00:00:30Z")),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedHostObservationExpectation.from(
                                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                        "denied login",
                                        "FulcrumBannedOne"),
                                Instant.parse("2026-06-17T00:00:01Z")))));

        assertTrue(exception.getMessage().contains("Paper host session-attached observations"));
        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
        assertTrue(exception.getMessage().contains("observedAt"));
    }

    @Test
    void verifierMatchesPresenceAuthorityLiveStateToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                %s
                Processed a total of 3 messages
                """.formatted(
                PresenceAuthorityWireCodec.encodeState(new PresenceState(Optional.empty())),
                presenceAuthorityState(
                        first,
                        PresenceLifecycleStatus.LIVE,
                        first.sessionId(),
                        first.routeId(),
                        Instant.parse("2026-06-17T00:00:00Z"),
                        Instant.parse("2026-06-17T00:05:00Z")),
                presenceAuthorityState(
                        scaleOut,
                        PresenceLifecycleStatus.LIVE,
                        scaleOut.sessionId(),
                        scaleOut.routeId(),
                        Instant.parse("2026-06-17T00:00:00Z"),
                        Instant.parse("2026-06-17T00:05:00Z")));

        LobbyClusterE2eVerifier.PresenceAuthorityStateResult result =
                LobbyClusterE2eVerifier.verifyPresenceAuthorityStateOutput(
                        output,
                        List.of(
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first),
                                        Instant.parse("2026-06-17T00:00:01Z")),
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "scale-out accepted login",
                                                "FulcrumBotFour",
                                                scaleOut),
                                        Instant.parse("2026-06-17T00:00:01Z"))),
                        List.of());

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsPresenceAuthorityStateWithWrongRoute() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = presenceAuthorityState(
                proof,
                PresenceLifecycleStatus.LIVE,
                proof.sessionId(),
                new RouteId("route-unexpected"),
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:05:00Z"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyPresenceAuthorityStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.EPOCH)),
                        List.of()));

        assertTrue(exception.getMessage().contains("routeId"));
    }

    @Test
    void verifierRejectsFreshPresenceAuthorityStateForDeniedLogin() {
        String output = presenceAuthorityState(
                "FulcrumBannedOne",
                PresenceLifecycleStatus.LIVE,
                new SessionId("session-lobby-banned"),
                new RouteId("route-lobby-banned"),
                Instant.parse("2026-06-17T00:00:30Z"),
                Instant.parse("2026-06-17T00:05:30Z"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyPresenceAuthorityStateOutput(
                        output,
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedPresenceAuthorityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Instant.parse("2026-06-17T00:00:01Z")))));

        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("LIVE Presence"));
    }

    @Test
    void verifierIgnoresStalePresenceAuthorityStateForDeniedLogin() {
        String output = presenceAuthorityState(
                "FulcrumBannedOne",
                PresenceLifecycleStatus.LIVE,
                new SessionId("session-lobby-banned"),
                new RouteId("route-lobby-banned"),
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:05:00Z"));

        LobbyClusterE2eVerifier.PresenceAuthorityStateResult result =
                LobbyClusterE2eVerifier.verifyPresenceAuthorityStateOutput(
                        output,
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedPresenceAuthorityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Instant.parse("2026-06-17T00:00:01Z"))));

        assertEquals(0, result.matchedCount());
        assertEquals(1, result.recordsScanned());
    }

    @Test
    void verifierMatchesStandardCapabilityStateToLobbyProofsAndDeniedLogin() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");

        LobbyClusterE2eVerifier.StandardCapabilityStateResult result =
                LobbyClusterE2eVerifier.verifyStandardCapabilityStateOutput(
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                playerProfileState(first.subjectId(), "Fulcrum Bot One"),
                                playerProfileState(scaleOut.subjectId(), "Fulcrum Bot Four")),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                rankState(first.subjectId(), "Admin"),
                                rankState(scaleOut.subjectId(), "Admin")),
                        punishmentState("FulcrumBannedOne", "Banned from the lobby", "2099-01-01T00:00:00Z"),
                        List.of(
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        first,
                                        "Fulcrum Bot One",
                                        "Admin"),
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "scale-out accepted login",
                                        "FulcrumBotFour",
                                        scaleOut,
                                        "Fulcrum Bot Four",
                                        "Admin")),
                        Optional.of(LobbyClusterE2eVerifier.PunishmentCapabilityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Optional.of("Banned from the lobby"),
                                Instant.parse("2026-06-17T00:00:00Z"))));

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.profileRecordsScanned());
        assertEquals(2, result.rankRecordsScanned());
        assertEquals(1, result.punishmentRecordsScanned());
    }

    @Test
    void verifierRejectsStandardCapabilityStateWithWrongProfileDisplayName() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyStandardCapabilityStateOutput(
                        playerProfileState(proof.subjectId(), "Unexpected Name"),
                        rankState(proof.subjectId(), "Admin"),
                        "",
                        List.of(LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof,
                                "Fulcrum Bot One",
                                "Admin")),
                        Optional.empty()));

        assertTrue(exception.getMessage().contains("displayName"));
    }

    @Test
    void verifierRejectsStandardCapabilityStateWithWrongRank() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyStandardCapabilityStateOutput(
                        playerProfileState(proof.subjectId(), "Fulcrum Bot One"),
                        rankState(proof.subjectId(), "Member"),
                        "",
                        List.of(LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof,
                                "Fulcrum Bot One",
                                "Admin")),
                        Optional.empty()));

        assertTrue(exception.getMessage().contains("primaryRankKey"));
    }

    @Test
    void verifierRejectsMissingActivePunishmentStateForDeniedLogin() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyStandardCapabilityStateOutput(
                        playerProfileState(
                                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne"),
                                "Fulcrum Bot One"),
                        rankState(
                                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne"),
                                "Admin"),
                        punishmentState("FulcrumBannedOne", "expired", "2026-06-17T00:00:00Z"),
                        List.of(),
                        Optional.of(LobbyClusterE2eVerifier.PunishmentCapabilityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Optional.of("Banned from the lobby"),
                                Instant.parse("2026-06-17T00:00:01Z")))));

        assertTrue(exception.getMessage().contains("active standard punishment"));
    }

    @Test
    void verifierMatchesStandardCapabilityCommandLogsToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");

        LobbyClusterE2eVerifier.StandardCapabilityCommandLogResult result =
                LobbyClusterE2eVerifier.verifyStandardCapabilityCommandLogOutput(
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                playerProfileCommand(first.subjectId(), "Fulcrum Bot One"),
                                playerProfileCommand(scaleOut.subjectId(), "Fulcrum Bot Four")),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                rankCommand(first.subjectId(), "Admin"),
                                rankCommand(scaleOut.subjectId(), "Admin")),
                        punishmentCommand("FulcrumBannedOne", "Banned from the lobby", "2099-01-01T00:00:00Z"),
                        List.of(
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        first,
                                        "Fulcrum Bot One",
                                        "Admin"),
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "scale-out accepted login",
                                        "FulcrumBotFour",
                                        scaleOut,
                                        "Fulcrum Bot Four",
                                        "Admin")),
                        Optional.of(LobbyClusterE2eVerifier.PunishmentCapabilityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Optional.of("Banned from the lobby"),
                                Instant.parse("2026-06-17T00:00:00Z"))));

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.profileCommandsScanned());
        assertEquals(2, result.rankCommandsScanned());
        assertEquals(1, result.punishmentCommandsScanned());
    }

    @Test
    void verifierRejectsStandardCapabilityCommandLogWithWrongRank() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyStandardCapabilityCommandLogOutput(
                        playerProfileCommand(proof.subjectId(), "Fulcrum Bot One"),
                        rankCommand(proof.subjectId(), "Member"),
                        "",
                        List.of(LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof,
                                "Fulcrum Bot One",
                                "Admin")),
                        Optional.empty()));

        assertTrue(exception.getMessage().contains("rankKey"));
    }

    @Test
    void verifierMatchesPaperRewardCommandLogsToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");

        LobbyClusterE2eVerifier.RewardCommandLogResult result =
                LobbyClusterE2eVerifier.verifyRewardCommandLogOutput(
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                economyRewardCommand(first),
                                economyRewardCommand(scaleOut)),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                statsRewardCommand(first),
                                statsRewardCommand(scaleOut)),
                        List.of(
                                rewardExpectation("primary accepted login", "FulcrumBotOne", first),
                                rewardExpectation("scale-out accepted login", "FulcrumBotFour", scaleOut)),
                        List.of());

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.economyCommandsScanned());
        assertEquals(2, result.statsCommandsScanned());
    }

    @Test
    void verifierRejectsPaperRewardCommandLogWithWrongEconomyAmount() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRewardCommandLogOutput(
                        economyRewardCommand(proof, 125),
                        statsRewardCommand(proof),
                        List.of(rewardExpectation("primary accepted login", "FulcrumBotOne", proof)),
                        List.of()));

        assertTrue(exception.getMessage().contains("deltaMinorUnits"));
    }

    @Test
    void verifierMatchesConfiguredDuplicatePaperRewardCommandDelivery() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        LobbyClusterE2eVerifier.RewardCommandLogResult result =
                LobbyClusterE2eVerifier.verifyRewardCommandLogOutput(
                        """
                        %s
                        %s
                        """.formatted(
                                economyRewardCommand(proof),
                                economyRewardCommand(proof)),
                        """
                        %s
                        %s
                        """.formatted(
                                statsRewardCommand(proof),
                                statsRewardCommand(proof)),
                        List.of(rewardExpectation("primary accepted login", "FulcrumBotOne", proof, 2)),
                        List.of());

        assertEquals(1, result.matchedCount());
        assertEquals(2, result.economyCommandsScanned());
        assertEquals(2, result.statsCommandsScanned());
    }

    @Test
    void verifierRejectsPaperRewardCommandLogMissingConfiguredDuplicateDelivery() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRewardCommandLogOutput(
                        economyRewardCommand(proof),
                        statsRewardCommand(proof),
                        List.of(rewardExpectation("primary accepted login", "FulcrumBotOne", proof, 2)),
                        List.of()));

        assertTrue(exception.getMessage().contains("delivery copies"));
        assertTrue(exception.getMessage().contains("matched=1"));
    }

    @Test
    void verifierRejectsPaperRewardCommandLogWithExtraDuplicateDelivery() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRewardCommandLogOutput(
                        """
                        %s
                        %s
                        %s
                        """.formatted(
                                economyRewardCommand(proof),
                                economyRewardCommand(proof),
                                economyRewardCommand(proof)),
                        """
                        %s
                        %s
                        %s
                        """.formatted(
                                statsRewardCommand(proof),
                                statsRewardCommand(proof),
                                statsRewardCommand(proof)),
                        List.of(rewardExpectation("primary accepted login", "FulcrumBotOne", proof, 2)),
                        List.of()));

        assertTrue(exception.getMessage().contains("delivery copies=2"));
        assertTrue(exception.getMessage().contains("matched=3"));
    }

    @Test
    void verifierRejectsPaperRewardCommandLogForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRewardCommandLogOutput(
                        economyRewardCommand(deniedProof),
                        statsRewardCommand(deniedProof),
                        List.of(),
                        List.of(new LobbyClusterE2eVerifier.DeniedRewardCommandExpectation(
                                "denied login",
                                "FulcrumBannedOne",
                                deniedProof.subjectId(),
                                Instant.parse("2026-06-17T00:00:01Z")))));

        assertTrue(exception.getMessage().contains("Paper reward commands"));
        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
    }

    @Test
    void verifierMatchesPaperRewardStateToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");

        LobbyClusterE2eVerifier.RewardStateResult result =
                LobbyClusterE2eVerifier.verifyRewardStateOutput(
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                economyRewardState(first),
                                economyRewardState(scaleOut)),
                        """
                        Processed a total of 0 messages
                        %s
                        %s
                        Processed a total of 2 messages
                        """.formatted(
                                statsRewardState(first),
                                statsRewardState(scaleOut)),
                        List.of(
                                rewardExpectation("primary accepted login", "FulcrumBotOne", first),
                                rewardExpectation("scale-out accepted login", "FulcrumBotFour", scaleOut)),
                        List.of());

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.economyRecordsScanned());
        assertEquals(2, result.statsRecordsScanned());
    }

    @Test
    void verifierRejectsPaperRewardStateWithWrongEconomyBalance() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRewardStateOutput(
                        economyRewardState(proof, 500),
                        statsRewardState(proof),
                        List.of(rewardExpectation("primary accepted login", "FulcrumBotOne", proof)),
                        List.of()));

        assertTrue(exception.getMessage().contains("balanceMinorUnits"));
    }

    @Test
    void verifierRejectsPaperRewardStateForDeniedSubject() {
        PaperLobbyProofMessage deniedProof = lobbyProof(
                "FulcrumBannedOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRewardStateOutput(
                        economyRewardState(deniedProof),
                        statsRewardState(deniedProof),
                        List.of(),
                        List.of(new LobbyClusterE2eVerifier.DeniedRewardCommandExpectation(
                                "denied login",
                                "FulcrumBannedOne",
                                deniedProof.subjectId(),
                                Instant.parse("2026-06-17T00:00:01Z")))));

        assertTrue(exception.getMessage().contains("Paper reward authority state"));
        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("FulcrumBannedOne"));
    }

    @Test
    void verifierMatchesCassandraHotProjectionRowsToLobbyProofs() {
        Instant floor = Instant.parse("2026-06-17T00:00:01Z");
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");
        LobbyClusterE2eVerifier.RouteAttemptExpectation firstRoute =
                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                        "primary accepted login",
                        "FulcrumBotOne",
                        first,
                        floor);
        LobbyClusterE2eVerifier.RouteAttemptExpectation scaleOutRoute =
                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                        "scale-out accepted login",
                        "FulcrumBotFour",
                        scaleOut,
                        floor);
        LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation denied =
                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                        "denied login",
                        "FulcrumBannedOne",
                        floor);

        LobbyClusterE2eVerifier.CassandraHotProjectionResult result =
                LobbyClusterE2eVerifier.verifyCassandraHotProjectionOutput(
                        cassandraCopy(
                                "subject_id|presence_id|lifecycle_status|session_id|route_id|observed_at|expires_at",
                                cassandraPresenceRow(first),
                                cassandraPresenceRow(scaleOut)),
                        cassandraCopy(
                                "route_id|subject_id|target_session_id|target_instance_id|lifecycle_status|requested_at|completed_at",
                                cassandraRouteRow(first),
                                cassandraRouteRow(scaleOut)),
                        cassandraCopy(
                                "session_id|experience_id|slot_id|owner_instance_id|resolved_manifest_id|lifecycle_status|lease_expires_at|activated_at",
                                cassandraSessionRow(first),
                                cassandraSessionRow(scaleOut)),
                        cassandraCopy(
                                "subject_id|display_name|updated_by|observed_at",
                                cassandraProfileRow(first, "Fulcrum Bot One"),
                                cassandraProfileRow(scaleOut, "Fulcrum Bot Four")),
                        cassandraCopy(
                                "subject_id|primary_rank_key|permissions|updated_by|updated_at",
                                cassandraRankRow(first, "Admin"),
                                cassandraRankRow(scaleOut, "Admin")),
                        cassandraCopy(
                                "subject_id|reason|issued_by|issued_at|expires_at",
                                cassandraPunishmentRow("FulcrumBannedOne")),
                        cassandraCopy(
                                "subject_id|currency_key|balance_minor_units|last_entry_id|updated_by|updated_at",
                                cassandraEconomyRow(first),
                                cassandraEconomyRow(scaleOut)),
                        cassandraCopy(
                                "subject_id|stat_key|total|last_entry_id|updated_by|updated_at",
                                cassandraStatsRow(first),
                                cassandraStatsRow(scaleOut)),
                        new ExperienceId("experience-lobby"),
                        List.of(
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(firstRoute, floor),
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(scaleOutRoute, floor)),
                        List.of(LobbyClusterE2eVerifier.DeniedPresenceAuthorityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                floor)),
                        List.of(
                                LobbyClusterE2eVerifier.RouteAuthorityStateExpectation.from(firstRoute),
                                LobbyClusterE2eVerifier.RouteAuthorityStateExpectation.from(scaleOutRoute)),
                        List.of(LobbyClusterE2eVerifier.DeniedRouteAuthorityStateExpectation.from(denied)),
                        List.of(
                                LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(firstRoute, floor),
                                LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(scaleOutRoute, floor)),
                        List.of(
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        first,
                                        "Fulcrum Bot One",
                                        "Admin"),
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "scale-out accepted login",
                                        "FulcrumBotFour",
                                        scaleOut,
                                        "Fulcrum Bot Four",
                                        "Admin")),
                        Optional.of(LobbyClusterE2eVerifier.PunishmentCapabilityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Optional.of("Banned"),
                                floor)),
                        List.of(
                                rewardExpectation("primary accepted login", "FulcrumBotOne", first),
                                rewardExpectation("scale-out accepted login", "FulcrumBotFour", scaleOut)),
                        List.of(LobbyClusterE2eVerifier.DeniedRewardCommandExpectation.from(denied)));

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.presenceRowsScanned());
        assertEquals(2, result.routeRowsScanned());
        assertEquals(2, result.sessionRowsScanned());
        assertEquals(2, result.profileRowsScanned());
        assertEquals(2, result.rankRowsScanned());
        assertEquals(1, result.punishmentRowsScanned());
        assertEquals(2, result.economyRowsScanned());
        assertEquals(2, result.statsRowsScanned());
    }

    @Test
    void verifierRejectsCassandraHotProjectionWithWrongResolvedManifest() {
        Instant floor = Instant.parse("2026-06-17T00:00:01Z");
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage staleProjection = lobbyProof(
                "FulcrumBotOne",
                proof.instanceId(),
                proof.sessionId(),
                proof.slotId(),
                new ResolvedManifestId("manifest-unexpected"),
                proof.traceId());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyCassandraHotProjectionOutput(
                        "",
                        "",
                        cassandraCopy(
                                "session_id|experience_id|slot_id|owner_instance_id|resolved_manifest_id|lifecycle_status|lease_expires_at|activated_at",
                                cassandraSessionRow(staleProjection)),
                        "",
                        "",
                        "",
                        "",
                        "",
                        new ExperienceId("experience-lobby"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                floor)),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        List.of()));

        assertTrue(exception.getMessage().contains("resolved_manifest_id"));
    }

    @Test
    void verifierMatchesPostgresAuthorityRecordsToLobbyProofs() {
        Instant floor = Instant.parse("2026-06-17T00:00:01Z");
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");
        LobbyClusterE2eVerifier.RouteAttemptExpectation firstRoute =
                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                        "primary accepted login",
                        "FulcrumBotOne",
                        first,
                        floor);
        LobbyClusterE2eVerifier.RouteAttemptExpectation scaleOutRoute =
                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                        "scale-out accepted login",
                        "FulcrumBotFour",
                        scaleOut,
                        floor);
        LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation denied =
                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                        "denied login",
                        "FulcrumBannedOne",
                        floor);

        LobbyClusterE2eVerifier.PostgresAuthorityRecordResult result =
                LobbyClusterE2eVerifier.verifyPostgresAuthorityRecordOutput(
                        postgresCopy(
                                postgresAuthorityRecord(
                                        PresenceAuthority.aggregateId(first.subjectId()).value(),
                                        1,
                                        presenceAuthorityState(
                                                first,
                                                PresenceLifecycleStatus.LIVE,
                                                first.sessionId(),
                                                first.routeId(),
                                                Instant.parse("2026-06-17T00:00:30Z"),
                                                Instant.parse("2026-06-17T00:05:00Z"))),
                                postgresAuthorityRecord(
                                        PresenceAuthority.aggregateId(scaleOut.subjectId()).value(),
                                        1,
                                        presenceAuthorityState(
                                                scaleOut,
                                                PresenceLifecycleStatus.LIVE,
                                                scaleOut.sessionId(),
                                                scaleOut.routeId(),
                                                Instant.parse("2026-06-17T00:00:30Z"),
                                                Instant.parse("2026-06-17T00:05:00Z"))),
                                postgresAuthorityRecord(
                                        RouteAuthority.aggregateId(first.routeId()).value(),
                                        2,
                                        routeAuthorityState(first, first.sessionId(), first.instanceId())),
                                postgresAuthorityRecord(
                                        RouteAuthority.aggregateId(scaleOut.routeId()).value(),
                                        2,
                                        routeAuthorityState(scaleOut, scaleOut.sessionId(), scaleOut.instanceId())),
                                postgresAuthorityRecord(
                                        SessionAuthority.aggregateId(first.sessionId()).value(),
                                        2,
                                        sessionAuthorityState(
                                                first,
                                                SessionLifecycleStatus.ACTIVE,
                                                first.resolvedManifestId(),
                                                Optional.of(Instant.parse("2026-06-17T00:00:30Z")))),
                                postgresAuthorityRecord(
                                        SessionAuthority.aggregateId(scaleOut.sessionId()).value(),
                                        2,
                                        sessionAuthorityState(
                                                scaleOut,
                                                SessionLifecycleStatus.ACTIVE,
                                                scaleOut.resolvedManifestId(),
                                                Optional.of(Instant.parse("2026-06-17T00:00:30Z")))),
                                postgresAuthorityRecord(
                                        PlayerProfileAuthority.aggregateId(first.subjectId()).value(),
                                        1,
                                        playerProfileState(first.subjectId(), "Fulcrum Bot One")),
                                postgresAuthorityRecord(
                                        PlayerProfileAuthority.aggregateId(scaleOut.subjectId()).value(),
                                        1,
                                        playerProfileState(scaleOut.subjectId(), "Fulcrum Bot Four")),
                                postgresAuthorityRecord(
                                        RankAuthority.aggregateId(first.subjectId()).value(),
                                        1,
                                        rankState(first.subjectId(), "Admin")),
                                postgresAuthorityRecord(
                                        RankAuthority.aggregateId(scaleOut.subjectId()).value(),
                                        1,
                                        rankState(scaleOut.subjectId(), "Admin")),
                                postgresAuthorityRecord(
                                        PunishmentAuthority.aggregateId(
                                                LobbyCapabilitySeedProvisioner.offlineModeSubjectId(
                                                        "FulcrumBannedOne")).value(),
                                        1,
                                        punishmentState(
                                                "FulcrumBannedOne",
                                                "Banned from the lobby",
                                                "2026-06-18T00:00:00Z")),
                                postgresAuthorityRecord(
                                        EconomyAuthority.aggregateId(
                                                EconomyAuthority.accountId(first.subjectId(), "coins")).value(),
                                        1,
                                        economyRewardState(first)),
                                postgresAuthorityRecord(
                                        EconomyAuthority.aggregateId(
                                                EconomyAuthority.accountId(scaleOut.subjectId(), "coins")).value(),
                                        1,
                                        economyRewardState(scaleOut)),
                                postgresAuthorityRecord(
                                        StatsAuthority.aggregateId(
                                                StatsAuthority.counterId(first.subjectId(), "session-completions")).value(),
                                        1,
                                        statsRewardState(first)),
                                postgresAuthorityRecord(
                                        StatsAuthority.aggregateId(
                                                StatsAuthority.counterId(scaleOut.subjectId(), "session-completions")).value(),
                                        1,
                                        statsRewardState(scaleOut))),
                        new ExperienceId("experience-lobby"),
                        List.of(
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(firstRoute, floor),
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(scaleOutRoute, floor)),
                        List.of(LobbyClusterE2eVerifier.DeniedPresenceAuthorityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                floor)),
                        List.of(
                                LobbyClusterE2eVerifier.RouteAuthorityStateExpectation.from(firstRoute),
                                LobbyClusterE2eVerifier.RouteAuthorityStateExpectation.from(scaleOutRoute)),
                        List.of(LobbyClusterE2eVerifier.DeniedRouteAuthorityStateExpectation.from(denied)),
                        List.of(
                                LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(firstRoute, floor),
                                LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(scaleOutRoute, floor)),
                        List.of(
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        first,
                                        "Fulcrum Bot One",
                                        "Admin"),
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "scale-out accepted login",
                                        "FulcrumBotFour",
                                        scaleOut,
                                        "Fulcrum Bot Four",
                                        "Admin")),
                        Optional.of(LobbyClusterE2eVerifier.PunishmentCapabilityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Optional.of("Banned"),
                                floor)),
                        List.of(
                                rewardExpectation("primary accepted login", "FulcrumBotOne", first),
                                rewardExpectation("scale-out accepted login", "FulcrumBotFour", scaleOut)),
                        List.of(LobbyClusterE2eVerifier.DeniedRewardCommandExpectation.from(denied)));

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.presenceRowsScanned());
        assertEquals(2, result.routeRowsScanned());
        assertEquals(2, result.sessionRowsScanned());
        assertEquals(2, result.profileRowsScanned());
        assertEquals(2, result.rankRowsScanned());
        assertEquals(1, result.punishmentRowsScanned());
        assertEquals(2, result.economyRowsScanned());
        assertEquals(2, result.statsRowsScanned());
    }

    @Test
    void verifierRejectsPostgresAuthorityRecordWithWrongResolvedManifest() {
        Instant floor = Instant.parse("2026-06-17T00:00:01Z");
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyPostgresAuthorityRecordOutput(
                        postgresCopy(postgresAuthorityRecord(
                                SessionAuthority.aggregateId(proof.sessionId()).value(),
                                2,
                                sessionAuthorityState(
                                        proof,
                                        SessionLifecycleStatus.ACTIVE,
                                        new ResolvedManifestId("manifest-unexpected"),
                                        Optional.of(Instant.parse("2026-06-17T00:00:30Z"))))),
                        new ExperienceId("experience-lobby"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                floor)),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        List.of()));

        assertTrue(exception.getMessage().contains("resolvedManifestId"));
    }

    @Test
    void verifierMatchesValkeyCacheValuesToLobbyProofs() {
        Instant floor = Instant.parse("2026-06-17T00:00:01Z");
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scaleout"),
                new SlotId("slot-lobby-scaleout"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scaleout");
        LobbyClusterE2eVerifier.RouteAttemptExpectation firstRoute =
                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                        "primary accepted login",
                        "FulcrumBotOne",
                        first,
                        floor);
        LobbyClusterE2eVerifier.RouteAttemptExpectation scaleOutRoute =
                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                        "scale-out accepted login",
                        "FulcrumBotFour",
                        scaleOut,
                        floor);
        LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation denied =
                LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                        "denied login",
                        "FulcrumBannedOne",
                        floor);

        Map<String, String> values = new LinkedHashMap<>();
        values.put(
                PresenceAuthority.cacheKey(first.subjectId()),
                presenceAuthorityState(
                        first,
                        PresenceLifecycleStatus.LIVE,
                        first.sessionId(),
                        first.routeId(),
                        Instant.parse("2026-06-17T00:00:30Z"),
                        Instant.parse("2026-06-17T00:05:00Z")));
        values.put(
                PresenceAuthority.cacheKey(scaleOut.subjectId()),
                presenceAuthorityState(
                        scaleOut,
                        PresenceLifecycleStatus.LIVE,
                        scaleOut.sessionId(),
                        scaleOut.routeId(),
                        Instant.parse("2026-06-17T00:00:30Z"),
                        Instant.parse("2026-06-17T00:05:00Z")));
        values.put(
                PlayerProfileAuthority.cacheKey(first.subjectId()),
                playerProfileState(first.subjectId(), "Fulcrum Bot One"));
        values.put(
                PlayerProfileAuthority.cacheKey(scaleOut.subjectId()),
                playerProfileState(scaleOut.subjectId(), "Fulcrum Bot Four"));
        values.put(RankAuthority.cacheKey(first.subjectId()), rankState(first.subjectId(), "Admin"));
        values.put(RankAuthority.cacheKey(scaleOut.subjectId()), rankState(scaleOut.subjectId(), "Admin"));
        values.put(
                PunishmentAuthority.cacheKey(LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBannedOne")),
                punishmentState("FulcrumBannedOne", "Banned from the lobby", "2026-06-18T00:00:00Z"));
        values.put(
                EconomyAuthority.cacheKey(EconomyAuthority.accountId(first.subjectId(), "coins")),
                economyRewardState(first));
        values.put(
                EconomyAuthority.cacheKey(EconomyAuthority.accountId(scaleOut.subjectId(), "coins")),
                economyRewardState(scaleOut));
        values.put(
                StatsAuthority.cacheKey(StatsAuthority.counterId(first.subjectId(), "session-completions")),
                statsRewardState(first));
        values.put(
                StatsAuthority.cacheKey(StatsAuthority.counterId(scaleOut.subjectId(), "session-completions")),
                statsRewardState(scaleOut));

        LobbyClusterE2eVerifier.ValkeyCacheResult result =
                LobbyClusterE2eVerifier.verifyValkeyCacheValues(
                        values,
                        List.of(
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(firstRoute, floor),
                                LobbyClusterE2eVerifier.PresenceAuthorityStateExpectation.from(scaleOutRoute, floor)),
                        List.of(LobbyClusterE2eVerifier.DeniedPresenceAuthorityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                floor)),
                        List.of(
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        first,
                                        "Fulcrum Bot One",
                                        "Admin"),
                                LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                        "scale-out accepted login",
                                        "FulcrumBotFour",
                                        scaleOut,
                                        "Fulcrum Bot Four",
                                        "Admin")),
                        Optional.of(LobbyClusterE2eVerifier.PunishmentCapabilityStateExpectation.from(
                                "denied login",
                                "FulcrumBannedOne",
                                Optional.of("Banned"),
                                floor)),
                        List.of(
                                rewardExpectation("primary accepted login", "FulcrumBotOne", first),
                                rewardExpectation("scale-out accepted login", "FulcrumBotFour", scaleOut)),
                        List.of(LobbyClusterE2eVerifier.DeniedRewardCommandExpectation.from(denied)));

        assertEquals(2, result.matchedCount());
        assertEquals(14, result.cacheKeysChecked());
        assertEquals(2, result.presenceKeysChecked());
        assertEquals(2, result.profileKeysChecked());
        assertEquals(2, result.rankKeysChecked());
        assertEquals(1, result.punishmentKeysChecked());
        assertEquals(2, result.economyKeysChecked());
        assertEquals(2, result.statsKeysChecked());
    }

    @Test
    void verifierRejectsValkeyCacheWithWrongProfileDisplayName() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PlayerProfileAuthority.cacheKey(proof.subjectId()), playerProfileState(proof.subjectId(), "Wrong Name"));
        values.put(RankAuthority.cacheKey(proof.subjectId()), rankState(proof.subjectId(), "Admin"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyValkeyCacheValues(
                        values,
                        List.of(),
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.StandardCapabilityStateExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof,
                                "Fulcrum Bot One",
                                "Admin")),
                        Optional.empty(),
                        List.of(),
                        List.of()));

        assertTrue(exception.getMessage().contains("displayName"));
    }

    @Test
    void verifierAcceptsProjectionConsistencyWhenAllStateSurfacesArePresent() {
        LobbyClusterE2eVerifier.ProjectionConsistencyResult result =
                LobbyClusterE2eVerifier.verifyProjectionConsistencyEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.QueueRosterStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PresenceAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityStateResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardStateResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.CassandraHotProjectionResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PostgresAuthorityRecordResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.ValkeyCacheResult(2, 14, 2, 2, 2, 1, 2, 2)));

        assertEquals(2, result.routeAttemptStateMatches());
        assertEquals(2, result.cassandraHotProjectionMatches());
        assertEquals(2, result.postgresAuthorityRecordMatches());
        assertEquals(2, result.valkeyCacheMatches());
    }

    @Test
    void verifierRejectsProjectionConsistencyWhenAStateSurfaceIsMissing() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyProjectionConsistencyEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.empty(),
                        Optional.of(new LobbyClusterE2eVerifier.QueueRosterStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PresenceAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityStateResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardStateResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.CassandraHotProjectionResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PostgresAuthorityRecordResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.ValkeyCacheResult(2, 14, 2, 2, 2, 1, 2, 2))));

        assertTrue(exception.getMessage().contains("Projection consistency verification"));
        assertTrue(exception.getMessage().contains("Route authority Kafka state"));
    }

    @Test
    void verifierRejectsProjectionConsistencyWhenAStateSurfaceHasNoMatches() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyProjectionConsistencyEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.QueueRosterStateResult(0, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PresenceAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityStateResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardStateResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.CassandraHotProjectionResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PostgresAuthorityRecordResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.ValkeyCacheResult(2, 14, 2, 2, 2, 1, 2, 2))));

        assertTrue(exception.getMessage().contains("Projection consistency verification"));
        assertTrue(exception.getMessage().contains("queue-roster Kafka state"));
        assertTrue(exception.getMessage().contains("positive matches"));
    }

    @Test
    void verifierRejectsProjectionConsistencyWhenSubjectEvidenceCountsDiverge() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyProjectionConsistencyEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(1, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.QueueRosterStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PresenceAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityStateResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardStateResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.CassandraHotProjectionResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PostgresAuthorityRecordResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.ValkeyCacheResult(2, 14, 2, 2, 2, 1, 2, 2))));

        assertTrue(exception.getMessage().contains("Projection consistency verification"));
        assertTrue(exception.getMessage().contains("accepted login count 2"));
        assertTrue(exception.getMessage().contains("Route authority Kafka state expected 2 got 1"));
    }

    @Test
    void verifierRejectsProjectionConsistencyWhenSessionEvidenceCountsDiverge() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyProjectionConsistencyEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.QueueRosterStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PresenceAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityStateResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardStateResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.CassandraHotProjectionResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.PostgresAuthorityRecordResult(2, 2, 2, 1, 2, 2, 1, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.ValkeyCacheResult(2, 14, 2, 2, 2, 1, 2, 2))));

        assertTrue(exception.getMessage().contains("Projection consistency verification"));
        assertTrue(exception.getMessage().contains("shared-shard allocation count 2"));
        assertTrue(exception.getMessage().contains("Session authority state expected 2 got 1"));
    }

    @Test
    void verifierAcceptsTraceCorrelationWhenAllTracedSurfacesArePresent() {
        LobbyClusterE2eVerifier.TraceCorrelationResult result =
                LobbyClusterE2eVerifier.verifyTraceCorrelationEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityCommandLogResult(2, 4)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LoginRoutingCommandLogResult(2, 4, 2, 2, 6, 8)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostRouteCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostObservationLogResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityCommandLogResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityCommandLogResult(1, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationCommandLogResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.AgonesFleetStateResult(
                                1,
                                1,
                                Set.of(new InstanceId("paper-instance-lobby-one")))));

        assertEquals(2, result.routeAttemptStateMatches());
        assertEquals(2, result.routeAuthorityCommandMatches());
        assertEquals(2, result.hostObservationMatches());
        assertEquals(1, result.agonesGameServerMetadataMatches());
    }

    @Test
    void verifierRejectsTraceCorrelationWhenATracedSurfaceIsMissing() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyTraceCorrelationEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.empty(),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LoginRoutingCommandLogResult(2, 4, 2, 2, 6, 8)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostRouteCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostObservationLogResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityCommandLogResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityCommandLogResult(1, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationCommandLogResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.AgonesFleetStateResult(
                                1,
                                1,
                                Set.of(new InstanceId("paper-instance-lobby-one"))))));

        assertTrue(exception.getMessage().contains("Trace correlation verification"));
        assertTrue(exception.getMessage().contains("Route authority command log"));
    }

    @Test
    void verifierRejectsTraceCorrelationWhenSubjectEvidenceCountsDiverge() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyTraceCorrelationEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityCommandLogResult(1, 4)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LoginRoutingCommandLogResult(2, 4, 2, 2, 6, 8)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostRouteCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostObservationLogResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityCommandLogResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityCommandLogResult(1, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationCommandLogResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(1, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.AgonesFleetStateResult(
                                1,
                                1,
                                Set.of(new InstanceId("paper-instance-lobby-one"))))));

        assertTrue(exception.getMessage().contains("Trace correlation verification"));
        assertTrue(exception.getMessage().contains("accepted login count 2"));
        assertTrue(exception.getMessage().contains("Route authority command log expected 2 got 1"));
    }

    @Test
    void verifierRejectsTraceCorrelationWhenSessionEvidenceCountsDiverge() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyTraceCorrelationEvidence(
                        Optional.of(new LobbyClusterE2eVerifier.RouteAttemptStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityCommandLogResult(2, 4)),
                        Optional.of(new LobbyClusterE2eVerifier.RouteAuthorityStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.LoginRoutingCommandLogResult(2, 4, 2, 2, 6, 8)),
                        Optional.of(new LobbyClusterE2eVerifier.LifecycleTraceStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostRouteCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.HostObservationLogResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.StandardCapabilityCommandLogResult(2, 2, 2, 1)),
                        Optional.of(new LobbyClusterE2eVerifier.RewardCommandLogResult(2, 2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SessionAuthorityCommandLogResult(1, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationCommandLogResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.SharedShardAllocationStateResult(2, 2)),
                        Optional.of(new LobbyClusterE2eVerifier.AgonesFleetStateResult(
                                2,
                                2,
                                Set.of(
                                        new InstanceId("paper-instance-lobby-one"),
                                        new InstanceId("paper-instance-lobby-two"))))));

        assertTrue(exception.getMessage().contains("Trace correlation verification"));
        assertTrue(exception.getMessage().contains("shared-shard allocation count 2"));
        assertTrue(exception.getMessage().contains("Session authority command log expected 2 got 1"));
    }

    @Test
    void verifierMatchesSessionAuthorityActiveStateToLobbyProof() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                Processed a total of 2 messages
                """.formatted(
                sessionAuthorityState(
                        proof,
                        SessionLifecycleStatus.PREPARING,
                        proof.resolvedManifestId(),
                        Optional.empty()),
                sessionAuthorityState(
                        proof,
                        SessionLifecycleStatus.ACTIVE,
                        proof.resolvedManifestId(),
                        Optional.of(Instant.parse("2026-06-17T00:00:30Z"))));

        LobbyClusterE2eVerifier.SessionAuthorityStateResult result =
                LobbyClusterE2eVerifier.verifySessionAuthorityStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.parse("2026-06-17T00:00:01Z"))));

        assertEquals(1, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsMultipleFreshActiveSessionsOnOnePaperInstance() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage conflictingSession = lobbyProof(
                "FulcrumBotOne",
                proof.instanceId(),
                new SessionId("session-lobby-conflict"),
                new SlotId("slot-lobby-conflict"),
                proof.resolvedManifestId(),
                proof.traceId());
        String output = """
                %s
                %s
                """.formatted(
                sessionAuthorityState(
                        proof,
                        SessionLifecycleStatus.ACTIVE,
                        proof.resolvedManifestId(),
                        Optional.of(Instant.parse("2026-06-17T00:00:30Z"))),
                sessionAuthorityState(
                        conflictingSession,
                        SessionLifecycleStatus.ACTIVE,
                        conflictingSession.resolvedManifestId(),
                        Optional.of(Instant.parse("2026-06-17T00:00:45Z"))));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySessionAuthorityStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.parse("2026-06-17T00:00:01Z")))));

        assertTrue(exception.getMessage().contains("exactly one fresh ACTIVE Session"));
        assertTrue(exception.getMessage().contains("paper-instance-lobby-one"));
        assertTrue(exception.getMessage().contains("session-lobby-conflict"));
    }

    @Test
    void verifierRejectsSessionAuthorityActiveStateWithWrongManifest() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = sessionAuthorityState(
                proof,
                SessionLifecycleStatus.ACTIVE,
                new ResolvedManifestId("manifest-unexpected"),
                Optional.of(Instant.parse("2026-06-17T00:00:30Z")));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySessionAuthorityStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.EPOCH))));

        assertTrue(exception.getMessage().contains("resolvedManifestId"));
    }

    @Test
    void verifierRejectsStaleSessionAuthorityActiveStateLease() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = sessionAuthorityState(
                proof,
                SessionLifecycleStatus.ACTIVE,
                proof.resolvedManifestId(),
                Optional.of(Instant.parse("2026-06-17T00:00:30Z")));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySessionAuthorityStateOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.parse("2026-06-17T00:05:01Z")))));

        assertTrue(exception.getMessage().contains("leaseExpiresAt"));
    }

    @Test
    void verifierMatchesSessionAuthorityCommandLogToLobbyProof() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                Processed a total of 2 messages
                """.formatted(
                sessionAuthorityCommand(
                        proof,
                        SessionAuthorityWireCodec.OPEN_COMMAND,
                        proof.traceId(),
                        Instant.parse("2026-06-17T00:05:00Z")),
                sessionAuthorityCommand(
                        proof,
                        SessionAuthorityWireCodec.ACTIVATE_COMMAND,
                        proof.traceId(),
                        Instant.parse("2026-06-17T00:05:00Z")));

        LobbyClusterE2eVerifier.SessionAuthorityCommandLogResult result =
                LobbyClusterE2eVerifier.verifySessionAuthorityCommandLogOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.parse("2026-06-17T00:00:01Z"))));

        assertEquals(1, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsSessionAuthorityCommandLogWithWrongTrace() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = sessionAuthorityCommand(
                proof,
                SessionAuthorityWireCodec.OPEN_COMMAND,
                "trace-unexpected",
                Instant.parse("2026-06-17T00:05:00Z"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySessionAuthorityCommandLogOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.EPOCH))));

        assertTrue(exception.getMessage().contains("traceId"));
    }

    @Test
    void verifierRejectsStaleSessionAuthorityCommandLogLease() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = """
                %s
                %s
                """.formatted(
                sessionAuthorityCommand(
                        proof,
                        SessionAuthorityWireCodec.OPEN_COMMAND,
                        proof.traceId(),
                        Instant.parse("2026-06-17T00:00:30Z")),
                sessionAuthorityCommand(
                        proof,
                        SessionAuthorityWireCodec.ACTIVATE_COMMAND,
                        proof.traceId(),
                        Instant.parse("2026-06-17T00:00:30Z")));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySessionAuthorityCommandLogOutput(
                        output,
                        List.of(LobbyClusterE2eVerifier.SessionAuthorityCommandExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof),
                                Instant.parse("2026-06-17T00:05:01Z")))));

        assertTrue(exception.getMessage().contains("leaseExpiresAt"));
    }

    @Test
    void verifierMatchesSharedShardAllocationCommandLogToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");
        String output = """
                Processed a total of 0 messages
                %s
                %s
                Processed a total of 2 messages
                """.formatted(
                sharedShardAllocationCommand(first, provisionerAllocationTrace()),
                sharedShardAllocationCommand(scaleOut, velocityAllocationTrace(scaleOut)));

        LobbyClusterE2eVerifier.SharedShardAllocationCommandLogResult result =
                LobbyClusterE2eVerifier.verifySharedShardAllocationCommandLogOutput(
                        output,
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(
                                LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first)),
                                LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "scale-out accepted login",
                                                "FulcrumBotFour",
                                                scaleOut))));

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsSharedShardAllocationCommandWithWrongManifest() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySharedShardAllocationCommandLogOutput(
                        sharedShardAllocationCommand(
                                proof,
                                new ResolvedManifestId("manifest-unexpected"),
                                new ExperienceId("experience-lobby"),
                                new PoolId("pool-lobby"),
                                provisionerAllocationTrace()),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof)))));

        assertTrue(exception.getMessage().contains("resolvedManifestId"));
    }

    @Test
    void verifierRejectsSharedShardAllocationCommandWithUnexpectedTraceOrigin() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySharedShardAllocationCommandLogOutput(
                        sharedShardAllocationCommand(proof, new TraceEnvelope(
                                "trace-untrusted-allocation",
                                "span-untrusted-allocation",
                                Optional.empty(),
                                Instant.parse("2026-06-17T00:00:00Z"),
                                "untrusted-allocation-source",
                                new InstanceId("instance-untrusted-allocation-source"))),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof)))));

        assertTrue(exception.getMessage().contains("originService"));
    }

    @Test
    void verifierMatchesSharedShardAllocationStateToLobbyProofs() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-scale-out");
        String output = """
                Processed a total of 0 messages
                recordType=route-attempt
                snapshot=false
                %s
                %s
                Processed a total of 2 messages
                """.formatted(
                sharedShardAllocationState(first),
                sharedShardAllocationState(scaleOut));

        LobbyClusterE2eVerifier.SharedShardAllocationStateResult result =
                LobbyClusterE2eVerifier.verifySharedShardAllocationStateOutput(
                        output,
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(
                                LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "primary accepted login",
                                                "FulcrumBotOne",
                                                first)),
                                LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                        LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                                "scale-out accepted login",
                                                "FulcrumBotFour",
                                                scaleOut))));

        assertEquals(2, result.matchedCount());
        assertEquals(2, result.recordsScanned());
    }

    @Test
    void verifierRejectsSharedShardAllocationStateWithWrongSlot() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        String output = sharedShardAllocationState(
                proof,
                new SlotId("slot-unexpected-lobby"),
                proof.instanceId(),
                proof.resolvedManifestId(),
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                HostInstanceKinds.PAPER);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySharedShardAllocationStateOutput(
                        output,
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{}),
                        List.of(LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof)))));

        assertTrue(exception.getMessage().contains("claim slotId"));
    }

    @Test
    void verifierRejectsSharedShardAllocationStateWithWrongExperience() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifySharedShardAllocationStateOutput(
                        sharedShardAllocationState(proof),
                        LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{
                                "--expected-lobby-experience-id=experience-unexpected"
                        }),
                        List.of(LobbyClusterE2eVerifier.SharedShardAllocationStateExpectation.from(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        proof)))));

        assertTrue(exception.getMessage().contains("experienceId"));
    }

    @Test
    void verifierRejectsControllerRouteAttemptStateForDeniedLoginSubject() {
        PaperLobbyProofMessage proof = lobbyProof(
                "BannedFulcrumBot",
                new InstanceId("paper-instance-lobby-banned"),
                new SessionId("session-lobby-banned"),
                new SlotId("slot-lobby-banned"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-banned");
        String output = ControllerStateWireCodec.encodeRouteAttempt(routeAttemptRecord(
                proof,
                RouteAttemptLifecycleStatus.ACKED,
                proof.slotId()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyRouteAttemptStateOutput(
                        output,
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                "denied login",
                                "BannedFulcrumBot"))));

        assertTrue(exception.getMessage().contains("denied login"));
        assertTrue(exception.getMessage().contains("BannedFulcrumBot"));
    }

    @Test
    void verifierIgnoresStaleControllerRouteAttemptStateForDeniedLoginSubject() {
        PaperLobbyProofMessage proof = lobbyProof(
                "BannedFulcrumBot",
                new InstanceId("paper-instance-lobby-banned"),
                new SessionId("session-lobby-banned"),
                new SlotId("slot-lobby-banned"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-banned");
        String output = ControllerStateWireCodec.encodeRouteAttempt(routeAttemptRecord(
                proof,
                RouteAttemptLifecycleStatus.ACKED,
                proof.slotId()));

        LobbyClusterE2eVerifier.RouteAttemptStateResult result =
                LobbyClusterE2eVerifier.verifyRouteAttemptStateOutput(
                        output,
                        List.of(),
                        List.of(LobbyClusterE2eVerifier.DeniedRouteAttemptExpectation.from(
                                "denied login",
                                "BannedFulcrumBot",
                                Instant.parse("2026-06-17T00:00:01Z"))));

        assertEquals(0, result.matchedCount());
        assertEquals(1, result.recordsScanned());
    }

    @Test
    void verifierMatchesAgonesGameServerStatesToLobbyProofInstances() {
        PaperLobbyProofMessage first = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");
        PaperLobbyProofMessage scaleOut = lobbyProof(
                "FulcrumBotFour",
                new InstanceId("paper-instance-lobby-two"),
                new SessionId("session-lobby-scale-out"),
                new SlotId("slot-lobby-scale-out"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        LobbyClusterE2eVerifier.AgonesGameServerStateResult result =
                LobbyClusterE2eVerifier.verifyAgonesGameServerStateOutput(
                        """
                        paper-instance-lobby-one|Allocated|pool-lobby|session-lobby-shared|slot-lobby-shared|manifest-lobby-bedrock-v1|trace-paper-session-lobby-shared
                        paper-instance-lobby-buffer|Ready|pool-lobby||||
                        paper-instance-lobby-two|Allocated|pool-lobby|session-lobby-scale-out|slot-lobby-scale-out|manifest-lobby-bedrock-v1|trace-paper-session-lobby-shared
                        """,
                        List.of(
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "primary accepted login",
                                        "FulcrumBotOne",
                                        first),
                                LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                        "scale-out accepted login",
                                        "FulcrumBotFour",
                                        scaleOut)),
                        2,
                        new PoolId("pool-lobby"));

        assertEquals(2, result.allocatedGameServers());
        assertTrue(result.proofInstanceIds().contains(new InstanceId("paper-instance-lobby-one")));
        assertTrue(result.proofInstanceIds().contains(new InstanceId("paper-instance-lobby-two")));
    }

    @Test
    void verifierRejectsAgonesGameServerStateWhenProofInstanceIsNotAllocated() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyAgonesGameServerStateOutput(
                        """
                        paper-instance-lobby-one|Ready|pool-lobby|session-lobby-shared|slot-lobby-shared|manifest-lobby-bedrock-v1|trace-paper-session-lobby-shared
                        paper-instance-lobby-two|Allocated|pool-lobby|session-lobby-scale-out|slot-lobby-scale-out|manifest-lobby-bedrock-v1|trace-paper-session-lobby-scale-out
                        """,
                        List.of(LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof)),
                        1,
                        new PoolId("pool-lobby")));

        assertTrue(exception.getMessage().contains("Allocated Agones GameServer"));
    }

    @Test
    void verifierRejectsAgonesGameServerMetadataWithWrongSession() {
        PaperLobbyProofMessage proof = lobbyProof(
                "FulcrumBotOne",
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                LobbyClusterE2eVerifier.verifyAgonesGameServerStateOutput(
                        """
                        paper-instance-lobby-one|Allocated|pool-lobby|session-unexpected|slot-lobby-shared|manifest-lobby-bedrock-v1|trace-paper-session-lobby-shared
                        """,
                        List.of(LobbyClusterE2eVerifier.RouteAttemptExpectation.from(
                                "primary accepted login",
                                "FulcrumBotOne",
                                proof)),
                        1,
                        new PoolId("pool-lobby")));

        assertTrue(exception.getMessage().contains("sessionId"));
    }

    @Test
    void verifierCanAssertDeniedLoginProbeForPunishmentGate() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_ACCEPTED,
                ExchangeKind.LOGIN_DENIED)) {
            LobbyClusterE2eVerifier.main(new String[]{
                    "--endpoint-host=127.0.0.1",
                    "--endpoint-port=" + server.port(),
                    "--login-username=FulcrumBotOne",
                    "--denied-login-username=BannedFulcrumBot",
                    "--denied-login-reason-contains=Banned by Fulcrum",
                    "--timeout=PT2S"
            });
        }
    }

    @Test
    void verifierCanAssertScaleOutAfterSharedLobbyFills() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_ACCEPTED,
                ExchangeKind.LOGIN_DENIED_NO_ROUTE,
                ExchangeKind.LOBBY_PROOF_SCALE_OUT_ACCEPTED)) {
            LobbyClusterE2eVerifier.main(new String[]{
                    "--endpoint-host=127.0.0.1",
                    "--endpoint-port=" + server.port(),
                    "--verify-scale-out=true",
                    "--scale-out-timeout=PT2S",
                    "--timeout=PT2S"
            });
        }
    }

    @Test
    void verifierRejectsSecondAcceptedLoginOnDifferentSession() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_DIFFERENT_SESSION)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("Expected second accepted login to join Session"));
        }
    }

    @Test
    void verifierRejectsSecondAcceptedLoginOnDifferentSlot() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_DIFFERENT_SLOT)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("Expected second accepted login to join Slot"));
        }
    }

    @Test
    void verifierRejectsMismatchedLobbySpawnPosition() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_WRONG_SPAWN)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("playerY"));
        }
    }

    @Test
    void verifierRejectsMismatchedLobbyResolvedManifest() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_WRONG_MANIFEST)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("resolvedManifestId"));
        }
    }

    @Test
    void verifierRejectsMismatchedLobbyTrace() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_WRONG_TRACE)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("traceId"));
        }
    }

    @Test
    void verifierRejectsMismatchedLobbyRoute() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_WRONG_ROUTE)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("routeId"));
        }
    }

    private static PaperLobbyProofMessage lobbyProof(
            String username,
            InstanceId instanceId,
            SessionId sessionId,
            SlotId slotId,
            ResolvedManifestId resolvedManifestId,
            String traceId) {
        SubjectId subjectId = LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username);
        String displayName = switch (username) {
            case "FulcrumBotTwo" -> "Fulcrum Bot Two";
            case "FulcrumBotFour" -> "Fulcrum Bot Four";
            default -> "Fulcrum Bot One";
        };
        return new PaperLobbyProofMessage(
                instanceId,
                sessionId,
                routeId(subjectId.value()),
                slotId,
                resolvedManifestId,
                traceId,
                subjectId,
                "world",
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F,
                displayName,
                Optional.of("Admin"),
                "[Admin] " + displayName + ": fulcrum-proof-chat");
    }

    private static String hostSessionAttachedObservation(
            PaperLobbyProofMessage proof,
            String traceId,
            Instant observedAt) {
        return HostObservationWireCodec.encode(HostObservationFactory.sessionAttached(new HostSessionAttachment(
                new HostInstanceIdentity(
                        proof.instanceId(),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-lobby"),
                        new MachineRef("machine-lobby-test"),
                        new PrincipalId("principal-fulcrum-paper-agent")),
                proof.routeId(),
                proof.subjectId(),
                proof.sessionId(),
                trace(traceId),
                observedAt)));
    }

    private static String presenceAuthorityState(
            PaperLobbyProofMessage proof,
            PresenceLifecycleStatus status,
            SessionId sessionId,
            RouteId routeId,
            Instant observedAt,
            Instant expiresAt) {
        return presenceAuthorityState(proof.subjectId(), status, sessionId, routeId, observedAt, expiresAt);
    }

    private static String presenceAuthorityState(
            String username,
            PresenceLifecycleStatus status,
            SessionId sessionId,
            RouteId routeId,
            Instant observedAt,
            Instant expiresAt) {
        return presenceAuthorityState(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username),
                status,
                sessionId,
                routeId,
                observedAt,
                expiresAt);
    }

    private static String presenceAuthorityState(
            SubjectId subjectId,
            PresenceLifecycleStatus status,
            SessionId sessionId,
            RouteId routeId,
            Instant observedAt,
            Instant expiresAt) {
        String suffix = subjectId.value().toString().replace("-", "");
        PresenceSnapshot snapshot = new PresenceSnapshot(
                velocityPresenceId(subjectId),
                subjectId,
                new InstanceId("velocity-instance-lobby"),
                new PresenceOwnerToken("owner-token-velocity-login-" + suffix),
                1,
                status,
                Optional.of(sessionId),
                Optional.of(routeId),
                observedAt,
                expiresAt,
                Optional.empty(),
                Optional.empty());
        return PresenceAuthorityWireCodec.encodeState(new PresenceState(snapshot));
    }

    private static String playerProfileState(SubjectId subjectId, String displayName) {
        PlayerProfileSnapshot snapshot = new PlayerProfileSnapshot(
                subjectId,
                displayName,
                new PrincipalId("principal-lobby-capability-seed"),
                Instant.parse("2026-01-01T00:00:00Z"));
        return StandardCapabilityAuthorityWireCodec.encodePlayerProfileState(new PlayerProfileState(snapshot));
    }

    private static String rankState(SubjectId subjectId, String rankKey) {
        EffectiveRankSnapshot snapshot = new EffectiveRankSnapshot(
                subjectId,
                rankKey,
                "rank:" + rankKey,
                new PrincipalId("principal-lobby-capability-seed"),
                Instant.parse("2026-01-01T00:00:00Z"));
        return StandardCapabilityAuthorityWireCodec.encodeRankState(new RankState(snapshot));
    }

    private static String punishmentState(String username, String reason, String expiresAt) {
        ActivePunishmentSnapshot snapshot = new ActivePunishmentSnapshot(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username),
                "punishment-lobby-ban",
                reason,
                new PrincipalId("principal-lobby-capability-seed"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse(expiresAt));
        return StandardCapabilityAuthorityWireCodec.encodePunishmentState(new PunishmentState(snapshot));
    }

    private static String playerProfileCommand(SubjectId subjectId, String displayName) {
        return standardCapabilityCommand(subjectId, "profile", "standard.player-profile.v1", "upsert-profile")
                + """
                displayName=%s
                observedAt=2026-01-01T00:00:00Z
                payloadExpectedRevision=0
                """.formatted(displayName);
    }

    private static String rankCommand(SubjectId subjectId, String rankKey) {
        return standardCapabilityCommand(subjectId, "rank", "standard.rank.v1", "grant-rank")
                + """
                rankKey=%s
                grantedAt=2026-01-01T00:00:00Z
                payloadExpectedRevision=0
                """.formatted(rankKey);
    }

    private static String punishmentCommand(String username, String reason, String expiresAt) {
        SubjectId subjectId = LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username);
        return standardCapabilityCommand(subjectId, "punishment", "standard.punishment.v1", "issue-punishment")
                + """
                punishmentId=punishment-lobby-ban
                reason=%s
                issuedAt=2026-01-01T00:00:00Z
                expiresAt=%s
                payloadExpectedRevision=0
                """.formatted(reason, expiresAt);
    }

    private static String standardCapabilityCommand(
            SubjectId subjectId,
            String commandKind,
            String contractName,
            String commandName) {
        String suffix = shortSubject(subjectId);
        return """
                commandId=command-lobby-capability-seed-%s-%s
                idempotencyKey=idem-lobby-capability-seed-%s-%s
                principalId=principal-lobby-capability-seed
                aggregateId=%s:%s
                contractName=%s
                commandName=%s
                traceId=trace-lobby-capability-seed-%s
                spanId=span-lobby-capability-seed-%s-%s
                parentSpanId=
                traceCreatedAt=2026-06-17T00:00:00Z
                originService=capability-seed-provisioner
                originInstanceId=instance-lobby-capability-seed
                deadlineAt=2026-06-17T00:00:30Z
                authenticatedPrincipal=principal-lobby-capability-seed
                fencingEpoch=1
                expectedRevision=0
                payloadFingerprint=fingerprint
                receivedAt=2026-06-17T00:00:00Z
                subjectId=%s
                """.formatted(
                commandKind,
                suffix,
                commandKind,
                suffix,
                commandKind,
                subjectId.value(),
                contractName,
                commandName,
                suffix,
                commandKind,
                suffix,
                subjectId.value());
    }

    private static LobbyClusterE2eVerifier.RewardCommandExpectation rewardExpectation(
            String label,
            String username,
            PaperLobbyProofMessage proof) {
        return rewardExpectation(label, username, proof, 1);
    }

    private static LobbyClusterE2eVerifier.RewardCommandExpectation rewardExpectation(
            String label,
            String username,
            PaperLobbyProofMessage proof,
            int expectedDeliveryCopies) {
        return new LobbyClusterE2eVerifier.RewardCommandExpectation(
                label,
                username,
                proof.subjectId(),
                proof.routeId(),
                proof.sessionId(),
                proof.instanceId(),
                new ExperienceId("experience-lobby"),
                "coins",
                250,
                "session-completions",
                expectedDeliveryCopies,
                Instant.parse("2026-06-17T00:00:01Z"));
    }

    private static String economyRewardCommand(PaperLobbyProofMessage proof) {
        return economyRewardCommand(proof, 250);
    }

    private static String economyRewardCommand(PaperLobbyProofMessage proof, long amountMinorUnits) {
        Instant occurredAt = Instant.parse("2026-06-17T00:00:30Z");
        PostLedgerEntry payload = new PostLedgerEntry(
                proof.subjectId(),
                "coins",
                amountMinorUnits,
                "session-reward:" + proof.sessionId().value(),
                occurredAt,
                0);
        return StandardCapabilityAuthorityWireCodec.encodeEconomyCommand(new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-paper-reward-economy-" + rewardSuffix(proof)),
                        new IdempotencyKey("idem-paper-reward-economy-" + rewardSuffix(proof)),
                        new PrincipalId("principal-fulcrum-paper-agent"),
                        EconomyAuthority.aggregateId(payload.accountId()),
                        EconomyContracts.CONTRACT,
                        new CommandName(StandardCapabilityAuthorityWireCodec.POST_LEDGER_ENTRY_COMMAND),
                        paperAttachTrace(proof, occurredAt),
                        Optional.of(occurredAt.plusSeconds(30)),
                        payload),
                new PrincipalId("principal-fulcrum-paper-agent"),
                1,
                Optional.empty(),
                rewardFingerprint(
                        "economy",
                        proof.sessionId(),
                        proof.routeId(),
                        proof.subjectId(),
                        payload.currencyKey(),
                        payload.deltaMinorUnits(),
                        occurredAt),
                occurredAt));
    }

    private static String statsRewardCommand(PaperLobbyProofMessage proof) {
        Instant occurredAt = Instant.parse("2026-06-17T00:00:30Z");
        RecordStatDelta payload = new RecordStatDelta(
                proof.subjectId(),
                new ExperienceId("experience-lobby"),
                "session-completions",
                1,
                occurredAt,
                0);
        return StandardCapabilityAuthorityWireCodec.encodeStatsCommand(new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-paper-reward-stats-" + rewardSuffix(proof)),
                        new IdempotencyKey("idem-paper-reward-stats-" + rewardSuffix(proof)),
                        new PrincipalId("principal-fulcrum-paper-agent"),
                        StatsAuthority.aggregateId(payload.counterId()),
                        StatsContracts.CONTRACT,
                        new CommandName(StandardCapabilityAuthorityWireCodec.RECORD_STAT_DELTA_COMMAND),
                        paperAttachTrace(proof, occurredAt),
                        Optional.of(occurredAt.plusSeconds(30)),
                        payload),
                new PrincipalId("principal-fulcrum-paper-agent"),
                1,
                Optional.empty(),
                rewardFingerprint(
                        "stats",
                        proof.sessionId(),
                        proof.routeId(),
                        proof.subjectId(),
                        payload.statKey(),
                        payload.delta(),
                        occurredAt),
                occurredAt));
    }

    private static String economyRewardState(PaperLobbyProofMessage proof) {
        return economyRewardState(proof, 250);
    }

    private static String economyRewardState(PaperLobbyProofMessage proof, long balanceMinorUnits) {
        Instant updatedAt = Instant.parse("2026-06-17T00:00:30Z");
        EconomyBalanceSnapshot snapshot = new EconomyBalanceSnapshot(
                EconomyAuthority.accountId(proof.subjectId(), "coins"),
                balanceMinorUnits,
                "idem-paper-reward-economy-" + rewardSuffix(proof),
                new PrincipalId("principal-fulcrum-paper-agent"),
                updatedAt);
        return StandardCapabilityAuthorityWireCodec.encodeEconomyState(new EconomyState(
                Optional.of(snapshot),
                List.of()));
    }

    private static String statsRewardState(PaperLobbyProofMessage proof) {
        Instant updatedAt = Instant.parse("2026-06-17T00:00:30Z");
        StatsCounterSnapshot snapshot = new StatsCounterSnapshot(
                StatsAuthority.counterId(proof.subjectId(), "session-completions"),
                1,
                "idem-paper-reward-stats-" + rewardSuffix(proof),
                new PrincipalId("principal-fulcrum-paper-agent"),
                updatedAt);
        return StandardCapabilityAuthorityWireCodec.encodeStatsState(new StatsState(
                Optional.of(snapshot),
                List.of()));
    }

    private static String cassandraCopy(String... rows) {
        return "Using 1 child processes\n"
                + String.join("\n", rows)
                + "\n"
                + rows.length + " rows exported to 1 files in 0.001 seconds.";
    }

    private static String postgresCopy(String... rows) {
        return String.join("\n", rows);
    }

    private static String postgresAuthorityRecord(String aggregateId, long revision, String statePayload) {
        return aggregateId + "|" + revision + "|1|"
                + HexFormat.of().formatHex(statePayload.getBytes(StandardCharsets.UTF_8));
    }

    private static String cassandraPresenceRow(PaperLobbyProofMessage proof) {
        return cassandraRow(
                proof.subjectId().value(),
                velocityPresenceId(proof.subjectId()).value(),
                PresenceLifecycleStatus.LIVE.name(),
                proof.sessionId().value(),
                proof.routeId().value(),
                "2026-06-17T00:00:30Z",
                "2026-06-17T00:05:00Z");
    }

    private static String cassandraRouteRow(PaperLobbyProofMessage proof) {
        return cassandraRow(
                proof.routeId().value(),
                proof.subjectId().value(),
                proof.sessionId().value(),
                proof.instanceId().value(),
                RouteLifecycleStatus.ACKNOWLEDGED.name(),
                "2026-06-17T00:00:00Z",
                "2026-06-17T00:00:30Z");
    }

    private static String cassandraSessionRow(PaperLobbyProofMessage proof) {
        return cassandraRow(
                proof.sessionId().value(),
                "experience-lobby",
                proof.slotId().value(),
                proof.instanceId().value(),
                proof.resolvedManifestId().value(),
                SessionLifecycleStatus.ACTIVE.name(),
                "2026-06-17T00:05:00Z",
                "2026-06-17T00:00:30Z");
    }

    private static String cassandraProfileRow(PaperLobbyProofMessage proof, String displayName) {
        return cassandraRow(
                proof.subjectId().value(),
                displayName,
                "principal-lobby-capability-seed",
                "2026-01-01T00:00:00Z");
    }

    private static String cassandraRankRow(PaperLobbyProofMessage proof, String rankKey) {
        return cassandraRow(
                proof.subjectId().value(),
                rankKey,
                "rank:" + rankKey,
                "principal-lobby-capability-seed",
                "2026-01-01T00:00:00Z");
    }

    private static String cassandraPunishmentRow(String username) {
        return cassandraRow(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username).value(),
                "Banned from the lobby",
                "principal-lobby-capability-seed",
                "2026-01-01T00:00:00Z",
                "2026-06-18T00:00:00Z");
    }

    private static String cassandraEconomyRow(PaperLobbyProofMessage proof) {
        return cassandraRow(
                proof.subjectId().value(),
                "coins",
                250,
                "idem-paper-reward-economy-" + rewardSuffix(proof),
                "principal-fulcrum-paper-agent",
                "2026-06-17T00:00:30Z");
    }

    private static String cassandraStatsRow(PaperLobbyProofMessage proof) {
        return cassandraRow(
                proof.subjectId().value(),
                "session-completions",
                1,
                "idem-paper-reward-stats-" + rewardSuffix(proof),
                "principal-fulcrum-paper-agent",
                "2026-06-17T00:00:30Z");
    }

    private static String cassandraRow(Object... values) {
        StringBuilder row = new StringBuilder();
        for (Object value : values) {
            if (!row.isEmpty()) {
                row.append('|');
            }
            row.append(value);
        }
        return row.toString();
    }

    private static String rewardSuffix(PaperLobbyProofMessage proof) {
        return compact(proof.sessionId().value()) + "-" + compact(proof.subjectId().value().toString());
    }

    private static TraceEnvelope paperAttachTrace(PaperLobbyProofMessage proof, Instant occurredAt) {
        return new TraceEnvelope(
                paperAttachTraceId(proof),
                "span-paper-attach",
                Optional.empty(),
                occurredAt,
                "paper-agent",
                proof.instanceId());
    }

    private static String paperAttachTraceId(PaperLobbyProofMessage proof) {
        return "trace-paper-attach-" + proof.subjectId().value();
    }

    private static String rewardFingerprint(
            String family,
            SessionId sessionId,
            RouteId routeId,
            SubjectId subjectId,
            String key,
            long delta,
            Instant occurredAt) {
        String value = new StringBuilder()
                .append("family=").append(family).append('\n')
                .append("sessionId=").append(sessionId.value()).append('\n')
                .append("routeId=").append(routeId.value()).append('\n')
                .append("subjectId=").append(subjectId.value()).append('\n')
                .append("key=").append(key).append('\n')
                .append("delta=").append(delta).append('\n')
                .append("occurredAt=").append(occurredAt).append('\n')
                .toString();
        return sha256(value);
    }

    private static String sharedShardAllocationCommand(
            PaperLobbyProofMessage proof,
            TraceEnvelope traceEnvelope) {
        return sharedShardAllocationCommand(
                proof,
                proof.resolvedManifestId(),
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                traceEnvelope);
    }

    private static String sharedShardAllocationCommand(
            PaperLobbyProofMessage proof,
            ResolvedManifestId resolvedManifestId,
            ExperienceId experienceId,
            PoolId poolId,
            TraceEnvelope traceEnvelope) {
        SharedShardAllocationRequest request = new SharedShardAllocationRequest(
                experienceId,
                poolId,
                proof.sessionId(),
                resolvedManifestId,
                traceEnvelope,
                Instant.parse("2026-06-17T00:00:00Z"));
        return ControlCommandWireCodec.encodeSharedShardAllocationRequest(request);
    }

    private static TraceEnvelope provisionerAllocationTrace() {
        return new TraceEnvelope(
                LobbySharedShardAllocationProvisioner.DEFAULT_TRACE_ID,
                LobbySharedShardAllocationProvisioner.DEFAULT_SPAN_ID,
                Optional.empty(),
                Instant.parse("2026-01-01T00:00:00Z"),
                LobbySharedShardAllocationProvisioner.ORIGIN_SERVICE,
                new InstanceId(LobbySharedShardAllocationProvisioner.DEFAULT_INSTANCE_ID));
    }

    private static TraceEnvelope velocityAllocationTrace(PaperLobbyProofMessage proof) {
        String suffix = proof.subjectId().value().toString().replace("-", "");
        return new TraceEnvelope(
                "trace-velocity-login-" + suffix,
                "span-shared-shard-allocation-attempt-" + suffix,
                Optional.of("span-velocity-login-" + suffix),
                Instant.parse("2026-06-17T00:00:00Z"),
                "velocity-login-routing",
                new InstanceId("instance-velocity-login"));
    }

    private static String sharedShardAllocationState(PaperLobbyProofMessage proof) {
        return sharedShardAllocationState(
                proof,
                proof.slotId(),
                proof.instanceId(),
                proof.resolvedManifestId(),
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                HostInstanceKinds.PAPER);
    }

    private static String sharedShardAllocationState(
            PaperLobbyProofMessage proof,
            SlotId slotId,
            InstanceId instanceId,
            ResolvedManifestId resolvedManifestId,
            ExperienceId experienceId,
            PoolId poolId,
            String instanceKind) {
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        SharedShardAllocationRequest request = new SharedShardAllocationRequest(
                experienceId,
                poolId,
                proof.sessionId(),
                resolvedManifestId,
                trace(proof.traceId()),
                observedAt);
        HostAllocationClaim claim = new HostAllocationClaim(
                slotId,
                proof.sessionId(),
                new HostInstanceIdentity(
                        instanceId,
                        instanceKind,
                        poolId,
                        new MachineRef("machine-lobby-test"),
                        new PrincipalId("principal-fulcrum-paper-agent")),
                resolvedManifestId,
                new HostNetworkEndpoint("10.0.0.25", 25565),
                trace(proof.traceId()),
                observedAt);
        String requestFingerprint = experienceId.value()
                + "|" + poolId.value()
                + "|" + proof.sessionId().value()
                + "|" + resolvedManifestId.value();
        return ControllerStateWireCodec.encodeSharedShardAllocation(
                new ExternalControllerWorkerCatalog.StoredSharedShardAllocation(
                        requestFingerprint,
                        request,
                        claim));
    }

    private static String sessionAuthorityState(
            PaperLobbyProofMessage proof,
            SessionLifecycleStatus status,
            ResolvedManifestId resolvedManifestId,
            Optional<Instant> activatedAt) {
        Instant openedAt = Instant.parse("2026-06-17T00:00:00Z");
        SessionSnapshot snapshot = new SessionSnapshot(
                proof.sessionId(),
                new ExperienceId("experience-lobby"),
                proof.slotId(),
                proof.instanceId(),
                new SessionOwnerToken("owner-token-lobby"),
                1,
                resolvedManifestId,
                status,
                openedAt,
                Instant.parse("2026-06-17T00:05:00Z"),
                activatedAt,
                Optional.empty(),
                Optional.empty());
        return SessionAuthorityWireCodec.encodeState(new SessionState(snapshot));
    }

    private static String sessionAuthorityCommand(
            PaperLobbyProofMessage proof,
            String commandName,
            String traceId,
            Instant leaseExpiresAt) {
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        String common = """
                commandId=command-%s-%s
                idempotencyKey=idem-%s-%s
                principalId=principal-paper
                aggregateId=session:%s
                contractName=session
                commandName=%s
                traceId=%s
                spanId=span-paper-gameserver
                parentSpanId=
                traceCreatedAt=2026-06-17T00:00:00Z
                originService=paper-agent
                originInstanceId=%s
                deadlineAt=%s
                authenticatedPrincipal=principal-paper
                fencingEpoch=1
                expectedRevision=%d
                payloadFingerprint=fingerprint
                receivedAt=%s
                sessionId=%s
                """.formatted(
                commandName,
                proof.sessionId().value(),
                commandName,
                proof.sessionId().value(),
                proof.sessionId().value(),
                commandName,
                traceId,
                proof.instanceId().value(),
                leaseExpiresAt,
                SessionAuthorityWireCodec.OPEN_COMMAND.equals(commandName) ? 0 : 1,
                observedAt,
                proof.sessionId().value());
        if (SessionAuthorityWireCodec.OPEN_COMMAND.equals(commandName)) {
            return common + """
                    experienceId=experience-lobby
                    slotId=%s
                    ownerInstanceId=%s
                    ownerToken=owner-token-lobby
                    resolvedManifestId=%s
                    openedAt=%s
                    leaseExpiresAt=%s
                    """.formatted(
                    proof.slotId().value(),
                    proof.instanceId().value(),
                    proof.resolvedManifestId().value(),
                    observedAt,
                    leaseExpiresAt);
        }
        if (SessionAuthorityWireCodec.ACTIVATE_COMMAND.equals(commandName)) {
            return common + """
                    ownerToken=owner-token-lobby
                    ownerEpoch=1
                    activatedAt=%s
                    leaseExpiresAt=%s
                    """.formatted(observedAt, leaseExpiresAt);
        }
        throw new IllegalArgumentException("Unsupported Session authority command " + commandName);
    }

    private static String routeAuthorityCommand(
            PaperLobbyProofMessage proof,
            String commandName,
            String traceId,
            Instant routeTime) {
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        String common = """
                commandId=command-%s-%s
                idempotencyKey=idem-%s-%s
                principalId=principal-velocity
                aggregateId=route:%s
                contractName=route
                commandName=%s
                traceId=%s
                spanId=span-velocity-route
                parentSpanId=
                traceCreatedAt=2026-06-17T00:00:00Z
                originService=velocity-agent
                originInstanceId=velocity-instance-lobby
                deadlineAt=%s
                authenticatedPrincipal=principal-velocity
                fencingEpoch=1
                expectedRevision=%d
                payloadFingerprint=fingerprint
                receivedAt=%s
                routeId=%s
                subjectId=%s
                targetSessionId=%s
                targetInstanceId=%s
                """.formatted(
                commandName,
                proof.routeId().value(),
                commandName,
                proof.routeId().value(),
                proof.routeId().value(),
                commandName,
                traceId,
                routeTime,
                RouteAuthorityWireCodec.OPEN_COMMAND.equals(commandName) ? 0 : 1,
                observedAt,
                proof.routeId().value(),
                proof.subjectId().value(),
                proof.sessionId().value(),
                proof.instanceId().value());
        if (RouteAuthorityWireCodec.OPEN_COMMAND.equals(commandName)) {
            return common + """
                    requestedAt=%s
                    expiresAt=%s
                    """.formatted(observedAt, routeTime);
        }
        if (RouteAuthorityWireCodec.ACKNOWLEDGE_COMMAND.equals(commandName)) {
            return common + """
                    acknowledgedAt=%s
                    """.formatted(routeTime);
        }
        throw new IllegalArgumentException("Unsupported Route authority command " + commandName);
    }

    private static String routeAuthorityState(
            PaperLobbyProofMessage proof,
            SessionId sessionId,
            InstanceId targetInstanceId) {
        return RouteAuthorityWireCodec.encodeState(new RouteState(new RouteSnapshot(
                proof.routeId(),
                proof.subjectId(),
                sessionId,
                targetInstanceId,
                RouteLifecycleStatus.ACKNOWLEDGED,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-17T00:05:00Z"),
                Optional.of(Instant.parse("2026-06-17T00:00:30Z")))));
    }

    private static String queueRosterSubmitCommand(PaperLobbyProofMessage proof) {
        String subjectSuffix = subjectSuffix(proof.subjectId());
        String queueIntentId = "queue-intent-velocity-login-" + subjectSuffix;
        String compactQueueIntentId = queueIntentId.replace("-", "");
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        return """
                commandId=command-queue-velocity-login-submit-%s
                idempotencyKey=idem-queue-velocity-login-submit-%s
                principalId=principal-fulcrum-velocity-agent
                aggregateId=queue-roster:experience=experience-lobby|mode=none|pool=pool-lobby
                contractName=control.queue-roster
                commandName=ctrl.queue.submit-intent
                traceId=trace-queue-velocity-login-%s
                spanId=span-queue-velocity-login-submit-%s
                parentSpanId=
                traceCreatedAt=%s
                originService=velocity-login-routing
                originInstanceId=instance-velocity-login
                deadlineAt=2026-06-17T00:00:30Z
                authenticatedPrincipal=principal-fulcrum-velocity-agent
                fencingEpoch=1
                expectedRevision=0
                payloadFingerprint=queue-roster|command=ctrl.queue.submit-intent|id=%s|revision=0
                receivedAt=%s
                queueIntentId=%s
                subjectIds=%s
                experienceId=experience-lobby
                modeId=
                poolId=pool-lobby
                priority=0
                createdAt=%s
                queueDeadlineAt=2026-06-17T00:00:30Z
                """.formatted(
                compactQueueIntentId,
                compactQueueIntentId,
                compactQueueIntentId,
                compactQueueIntentId,
                observedAt,
                compactQueueIntentId,
                observedAt,
                queueIntentId,
                proof.subjectId().value(),
                observedAt);
    }

    private static String queueRosterFormCommand(PaperLobbyProofMessage proof) {
        String subjectSuffix = subjectSuffix(proof.subjectId());
        String queueIntentId = "queue-intent-velocity-login-" + subjectSuffix;
        String rosterIntentId = "roster-intent-velocity-login-" + subjectSuffix;
        String compactRosterIntentId = rosterIntentId.replace("-", "");
        Instant observedAt = Instant.parse("2026-06-17T00:00:00.001Z");
        return """
                commandId=command-queue-velocity-login-form-%s
                idempotencyKey=idem-queue-velocity-login-form-%s
                principalId=principal-fulcrum-velocity-agent
                aggregateId=queue-roster:experience=experience-lobby|mode=none|pool=pool-lobby
                contractName=control.queue-roster
                commandName=ctrl.queue.form-roster
                traceId=trace-queue-velocity-login-%s
                spanId=span-queue-velocity-login-form-%s
                parentSpanId=
                traceCreatedAt=%s
                originService=velocity-login-routing
                originInstanceId=instance-velocity-login
                deadlineAt=2026-06-17T00:00:30.001Z
                authenticatedPrincipal=principal-fulcrum-velocity-agent
                fencingEpoch=1
                expectedRevision=1
                payloadFingerprint=queue-roster|command=ctrl.queue.form-roster|id=%s|revision=1
                receivedAt=%s
                rosterIntentId=%s
                experienceId=experience-lobby
                modeId=
                poolId=pool-lobby
                queueIntentIds=%s
                maxSubjects=1
                formedAt=%s
                """.formatted(
                compactRosterIntentId,
                compactRosterIntentId,
                compactRosterIntentId,
                compactRosterIntentId,
                observedAt,
                compactRosterIntentId,
                observedAt,
                rosterIntentId,
                queueIntentId,
                observedAt);
    }

    private static String queueRosterState(PaperLobbyProofMessage... proofs) {
        Map<QueueIntentId, QueueIntentSnapshot> queueIntents = new LinkedHashMap<>();
        Map<RosterIntentId, RosterIntentSnapshot> rosterIntents = new LinkedHashMap<>();
        Instant createdAt = Instant.parse("2026-06-17T00:00:00Z");
        Instant formedAt = Instant.parse("2026-06-17T00:00:00.001Z");
        for (PaperLobbyProofMessage proof : proofs) {
            String subjectSuffix = subjectSuffix(proof.subjectId());
            QueueIntentId queueIntentId = new QueueIntentId("queue-intent-velocity-login-" + subjectSuffix);
            RosterIntentId rosterIntentId = new RosterIntentId("roster-intent-velocity-login-" + subjectSuffix);
            queueIntents.put(queueIntentId, new QueueIntentSnapshot(
                    queueIntentId,
                    List.of(proof.subjectId()),
                    new ExperienceId("experience-lobby"),
                    Optional.empty(),
                    new PoolId("pool-lobby"),
                    0,
                    createdAt,
                    createdAt.plusSeconds(30),
                    QueueIntentStatus.ROSTERED,
                    Optional.of(rosterIntentId),
                    queueRosterPayloadTrace(subjectSuffix, "span-queue-submit-velocity-login-" + subjectSuffix),
                    formedAt));
            rosterIntents.put(rosterIntentId, new RosterIntentSnapshot(
                    rosterIntentId,
                    List.of(queueIntentId),
                    List.of(proof.subjectId()),
                    new ExperienceId("experience-lobby"),
                    Optional.empty(),
                    new PoolId("pool-lobby"),
                    1,
                    RosterIntentStatus.FORMED,
                    queueRosterPayloadTrace(subjectSuffix, "span-roster-form-velocity-login-" + subjectSuffix),
                    formedAt));
        }
        return ControllerStateWireCodec.encodeQueueRoster(new QueueRosterControlRecord(
                new Revision(proofs.length * 2L),
                1L,
                new QueueRosterState(queueIntents, rosterIntents)));
    }

    private static TraceEnvelope queueRosterPayloadTrace(String subjectSuffix, String spanId) {
        return new TraceEnvelope(
                "trace-velocity-login-" + subjectSuffix,
                spanId,
                Optional.of("span-velocity-login-" + subjectSuffix),
                Instant.parse("2026-06-17T00:00:00Z"),
                "velocity-login-routing",
                new InstanceId("instance-velocity-login"));
    }

    private static String lifecycleTraceCommands(PaperLobbyProofMessage proof) {
        String subjectSuffix = subjectSuffix(proof.subjectId());
        Instant submittedAt = Instant.parse("2026-06-17T00:00:00Z");
        return lifecycleTraceCommand(
                proof,
                LifecyclePhase.QUEUE_INTENT_SUBMITTED,
                "queue-intent",
                "queue-intent-velocity-login-" + subjectSuffix,
                Optional.empty(),
                Optional.empty(),
                "queue",
                submittedAt)
                + lifecycleTraceCommand(
                        proof,
                        LifecyclePhase.ROSTER_INTENT_FORMED,
                        "roster-intent",
                        "roster-intent-velocity-login-" + subjectSuffix,
                        Optional.empty(),
                        Optional.empty(),
                        "roster",
                        submittedAt.plusMillis(1))
                + lifecycleTraceCommand(
                        proof,
                        LifecyclePhase.ALLOCATION_CLAIMED,
                        "slot",
                        proof.slotId().value(),
                        Optional.of(proof.sessionId()),
                        Optional.of(proof.resolvedManifestId()),
                        "allocation",
                        submittedAt)
                + lifecycleTraceCommand(
                        proof,
                        LifecyclePhase.ROUTE_ATTEMPT_CREATED,
                        "route-attempt",
                        routeAttemptId(proof.subjectId()),
                        Optional.of(proof.sessionId()),
                        Optional.of(proof.resolvedManifestId()),
                        "route-attempt",
                        submittedAt);
    }

    private static String lifecycleTraceCommand(
            PaperLobbyProofMessage proof,
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            Optional<SessionId> sessionId,
            Optional<ResolvedManifestId> resolvedManifestId,
            String commandSuffix,
            Instant observedAt) {
        String traceId = velocityLifecycleTraceId(proof);
        String compactTraceId = compact(traceId);
        TraceEnvelope commandTrace = new TraceEnvelope(
                traceId,
                "span-lifecycle-" + commandSuffix + "-" + compactTraceId,
                Optional.of("span-velocity-login-" + subjectSuffix(proof.subjectId())),
                observedAt,
                "velocity-login-routing",
                new InstanceId("instance-velocity-login"));
        RecordLifecycleObservation payload = new RecordLifecycleObservation(
                new LifecycleTraceId(traceId),
                phase,
                aggregateType,
                aggregateId,
                sessionId,
                resolvedManifestId,
                observedAt,
                commandTrace);
        PrincipalId principalId = new PrincipalId("principal-fulcrum-velocity-agent");
        return ControlCommandWireCodec.encodeLifecycleTraceRecord(new LifecycleTraceControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-lifecycle-velocity-login-" + commandSuffix + "-" + compactTraceId),
                        new IdempotencyKey("idem-lifecycle-velocity-login-" + commandSuffix + "-" + compactTraceId),
                        principalId,
                        ControlLifecycleNames.traceAggregateId(payload.traceId()),
                        ControlLifecycleNames.TRACE_CONTRACT,
                        ControlLifecycleNames.RECORD_LIFECYCLE_OBSERVATION,
                        commandTrace,
                        Optional.of(observedAt.plusSeconds(30)),
                        payload),
                principalId,
                1,
                Optional.empty(),
                "lifecycle-trace|phase=" + phase.name()
                        + "|traceId=" + traceId
                        + "|aggregateType=" + aggregateType
                        + "|aggregateId=" + aggregateId,
                observedAt));
    }

    private static String lifecycleTraceState(PaperLobbyProofMessage... proofs) {
        StringBuilder output = new StringBuilder();
        for (PaperLobbyProofMessage proof : proofs) {
            output.append(ControllerStateWireCodec.encodeLifecycleTrace(lifecycleTraceRecord(proof))).append('\n');
        }
        return output.toString();
    }

    private static LifecycleTraceControlRecord lifecycleTraceRecord(PaperLobbyProofMessage proof) {
        String subjectSuffix = subjectSuffix(proof.subjectId());
        String traceId = velocityLifecycleTraceId(proof);
        Instant submittedAt = Instant.parse("2026-06-17T00:00:00Z");
        Instant hostObservedAt = Instant.parse("2026-06-17T00:00:30Z");
        return new LifecycleTraceControlRecord(
                new Revision(6),
                1L,
                new LifecycleTraceRecord(
                        new LifecycleTraceId(traceId),
                        List.of(
                                lifecycleTraceEntry(
                                        1,
                                        traceId,
                                        LifecyclePhase.QUEUE_INTENT_SUBMITTED,
                                        "queue-intent",
                                        "queue-intent-velocity-login-" + subjectSuffix,
                                        Optional.empty(),
                                        Optional.empty(),
                                        "queue",
                                        submittedAt),
                                lifecycleTraceEntry(
                                        2,
                                        traceId,
                                        LifecyclePhase.ROSTER_INTENT_FORMED,
                                        "roster-intent",
                                        "roster-intent-velocity-login-" + subjectSuffix,
                                        Optional.empty(),
                                        Optional.empty(),
                                        "roster",
                                        submittedAt.plusMillis(1)),
                                lifecycleTraceEntry(
                                        3,
                                        traceId,
                                        LifecyclePhase.ALLOCATION_CLAIMED,
                                        "slot",
                                        proof.slotId().value(),
                                        Optional.of(proof.sessionId()),
                                        Optional.of(proof.resolvedManifestId()),
                                        "allocation",
                                        submittedAt),
                                lifecycleTraceEntry(
                                        4,
                                        traceId,
                                        LifecyclePhase.ROUTE_ATTEMPT_CREATED,
                                        "route-attempt",
                                        routeAttemptId(proof.subjectId()),
                                        Optional.of(proof.sessionId()),
                                        Optional.of(proof.resolvedManifestId()),
                                        "route-attempt",
                                        submittedAt),
                                lifecycleTraceEntry(
                                        5,
                                        traceId,
                                        LifecyclePhase.HOST_ATTACH_OBSERVED,
                                        "instance",
                                        proof.instanceId().value(),
                                        Optional.of(proof.sessionId()),
                                        Optional.of(proof.resolvedManifestId()),
                                        "host-attach",
                                        hostObservedAt),
                                lifecycleTraceEntry(
                                        6,
                                        traceId,
                                        LifecyclePhase.SESSION_ACTIVE,
                                        "session",
                                        proof.sessionId().value(),
                                        Optional.of(proof.sessionId()),
                                        Optional.of(proof.resolvedManifestId()),
                                        "session-active",
                                        hostObservedAt))));
    }

    private static LifecycleTraceEntry lifecycleTraceEntry(
            int sequence,
            String traceId,
            LifecyclePhase phase,
            String aggregateType,
            String aggregateId,
            Optional<SessionId> sessionId,
            Optional<ResolvedManifestId> resolvedManifestId,
            String spanSuffix,
            Instant observedAt) {
        return new LifecycleTraceEntry(
                sequence,
                phase,
                aggregateType,
                aggregateId,
                sessionId,
                resolvedManifestId,
                observedAt,
                new TraceEnvelope(
                        traceId,
                        "span-lifecycle-" + spanSuffix + "-" + compact(traceId),
                        Optional.empty(),
                        observedAt,
                        "lifecycle-trace-test",
                        new InstanceId("instance-lifecycle-trace-test")));
    }

    private static String velocityLifecycleTraceId(PaperLobbyProofMessage proof) {
        return "trace-velocity-login-" + subjectSuffix(proof.subjectId());
    }

    private static String presenceAuthorityCommand(PaperLobbyProofMessage proof) {
        String subjectSuffix = subjectSuffix(proof.subjectId());
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-06-17T00:05:00Z");
        return """
                commandId=command-presence-velocity-login-%s
                idempotencyKey=idem-presence-velocity-login-%s
                principalId=principal-fulcrum-velocity-agent
                aggregateId=subject:%s
                contractName=presence
                commandName=claim-presence
                traceId=trace-velocity-login-%s
                spanId=span-presence-velocity-login-%s
                parentSpanId=
                traceCreatedAt=%s
                originService=velocity-login-routing
                originInstanceId=instance-velocity-login
                deadlineAt=%s
                authenticatedPrincipal=principal-fulcrum-velocity-agent
                fencingEpoch=1
                expectedRevision=0
                payloadFingerprint=claim-presence|subjectId=%s|presenceId=presence-velocity-login-%s|sessionId=%s|routeId=%s
                receivedAt=%s
                subjectId=%s
                presenceId=presence-velocity-login-%s
                ownerInstanceId=instance-velocity-login
                ownerToken=owner-token-velocity-login-%s
                sessionId=%s
                routeId=%s
                observedAt=%s
                expiresAt=%s
                """.formatted(
                subjectSuffix,
                subjectSuffix,
                proof.subjectId().value(),
                subjectSuffix,
                subjectSuffix,
                observedAt,
                expiresAt,
                proof.subjectId().value(),
                subjectSuffix,
                proof.sessionId().value(),
                proof.routeId().value(),
                observedAt,
                proof.subjectId().value(),
                subjectSuffix,
                subjectSuffix,
                proof.sessionId().value(),
                proof.routeId().value(),
                observedAt,
                expiresAt);
    }

    private static String sharedShardPlacementRequest(PaperLobbyProofMessage proof) {
        String subjectSuffix = subjectSuffix(proof.subjectId());
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        return """
                placementAttemptId=placement-velocity-login-%s
                experienceId=experience-lobby
                poolId=pool-lobby
                agonesFleetName=fulcrum-lobby-paper
                targetCapacity=75
                hardCapacity=150
                resolvedManifestId=%s
                subjectId=%s
                presenceId=presence-velocity-login-%s
                capabilityScopeFingerprint=capability-scope-lobby
                requestedAt=%s
                traceId=trace-velocity-login-%s
                spanId=span-velocity-login-%s
                parentSpanId=
                traceCreatedAt=%s
                originService=velocity-login-routing
                originInstanceId=instance-velocity-login
                candidateCount=1
                candidate.0.instanceId=%s
                candidate.0.instanceKind=paper
                candidate.0.poolId=pool-lobby
                candidate.0.machineRef=machine-lobby-test
                candidate.0.principalId=principal-fulcrum-paper-agent
                candidate.0.resolvedManifestId=%s
                candidate.0.status=READY
                candidate.0.statusReason=
                candidate.0.updatedAt=%s
                candidate.0.sessionId=%s
                candidate.0.slotId=%s
                candidate.0.currentPresences=0
                candidate.0.hardCapacity=150
                candidate.0.acceptingPresences=true
                candidate.0.observedAt=%s
                """.formatted(
                subjectSuffix,
                proof.resolvedManifestId().value(),
                proof.subjectId().value(),
                subjectSuffix,
                observedAt,
                subjectSuffix,
                subjectSuffix,
                observedAt,
                proof.instanceId().value(),
                proof.resolvedManifestId().value(),
                observedAt,
                proof.sessionId().value(),
                proof.slotId().value(),
                observedAt);
    }

    private static String routeAttemptCommand(
            PaperLobbyProofMessage proof,
            String commandName,
            String commandSuffix,
            long expectedRevision,
            ResolvedManifestId resolvedManifestId) {
        String routeAttemptId = routeAttemptId(proof.subjectId());
        String routeAttemptSuffix = routeAttemptId.replace("-", "");
        Instant observedAt = Instant.parse("2026-06-17T00:00:00Z");
        String common = """
                commandId=command-route-velocity-login-%s-%s
                idempotencyKey=idem-route-velocity-login-%s-%s
                principalId=principal-fulcrum-velocity-agent
                aggregateId=route-attempt:%s
                contractName=control.route-attempt
                commandName=%s
                traceId=trace-route-velocity-login-%s
                spanId=span-route-velocity-login-%s-%s
                parentSpanId=
                traceCreatedAt=%s
                originService=velocity-login-routing
                originInstanceId=instance-velocity-login
                deadlineAt=2026-06-17T00:00:30Z
                authenticatedPrincipal=principal-fulcrum-velocity-agent
                fencingEpoch=1
                expectedRevision=%d
                payloadFingerprint=route-attempt|command=%s|routeAttemptId=%s|revision=%d
                receivedAt=%s
                routeAttemptId=%s
                """.formatted(
                commandSuffix,
                routeAttemptSuffix,
                commandSuffix,
                routeAttemptSuffix,
                routeAttemptId,
                commandName,
                routeAttemptSuffix,
                commandSuffix,
                routeAttemptSuffix,
                observedAt,
                expectedRevision,
                commandName,
                routeAttemptId,
                expectedRevision,
                observedAt,
                routeAttemptId);
        if ("ctrl.route.request-attempt".equals(commandName)) {
            return common + """
                    routeId=%s
                    sessionId=%s
                    allocationSlotId=%s
                    subjectIds=%s
                    proxyInstanceIds=instance-velocity-login
                    sourcePresenceId=presence-velocity-login-%s
                    targetInstanceId=%s
                    targetResolvedManifestId=%s
                    requestedAt=%s
                    payloadDeadlineAt=2026-06-17T00:00:30Z
                    """.formatted(
                    proof.routeId().value(),
                    proof.sessionId().value(),
                    proof.slotId().value(),
                    proof.subjectId().value(),
                    subjectSuffix(proof.subjectId()),
                    proof.instanceId().value(),
                    resolvedManifestId.value(),
                    observedAt);
        }
        if ("ctrl.route.issue-proxy".equals(commandName) || "ctrl.route.prepare-host".equals(commandName)) {
            return common + """
                    issuedAt=%s
                    """.formatted(observedAt.plusSeconds(expectedRevision));
        }
        throw new IllegalArgumentException("Unsupported route-attempt command " + commandName);
    }

    private static String velocityProxyRouteCommand(PaperLobbyProofMessage proof) {
        return velocityProxyRouteCommand(proof, proof.subjectId(), proof.traceId());
    }

    private static String velocityProxyRouteCommand(
            PaperLobbyProofMessage proof,
            SubjectId subjectId,
            String traceId) {
        return "proxy.route"
                + "|routeAttemptId=" + routeAttemptId(proof.subjectId())
                + "|routeId=" + proof.routeId().value()
                + "|subjectId=" + subjectId.value()
                + "|sessionId=" + proof.sessionId().value()
                + "|targetInstanceId=" + proof.instanceId().value()
                + "|traceId=" + traceId;
    }

    private static String paperHostRoutePrepareCommand(PaperLobbyProofMessage proof) {
        return paperHostRoutePrepareCommand(proof, proof.resolvedManifestId(), proof.traceId());
    }

    private static String paperHostRoutePrepareCommand(
            PaperLobbyProofMessage proof,
            ResolvedManifestId resolvedManifestId,
            String traceId) {
        return "host.route.prepare"
                + "|routeAttemptId=" + routeAttemptId(proof.subjectId())
                + "|routeId=" + proof.routeId().value()
                + "|sessionId=" + proof.sessionId().value()
                + "|resolvedManifestId=" + resolvedManifestId.value()
                + "|traceId=" + traceId;
    }

    private static String routeAttemptId(SubjectId subjectId) {
        return "route-attempt-velocity-login-" + subjectId.value().toString().replace("-", "");
    }

    private static String subjectSuffix(SubjectId subjectId) {
        return subjectId.value().toString().replace("-", "");
    }

    private static RouteAttemptControlRecord routeAttemptRecord(
            PaperLobbyProofMessage proof,
            RouteAttemptLifecycleStatus status,
            SlotId slotId) {
        Instant updatedAt = Instant.parse("2026-06-17T00:00:00Z");
        RouteAttemptSnapshot snapshot = new RouteAttemptSnapshot(
                new RouteAttemptId("route-attempt-" + proof.subjectId().value().toString().replace("-", "")),
                proof.routeId(),
                proof.sessionId(),
                slotId,
                List.of(proof.subjectId()),
                List.of(new InstanceId("velocity-instance-lobby")),
                new PresenceId("presence-" + proof.subjectId().value().toString().replace("-", "")),
                proof.instanceId(),
                proof.resolvedManifestId(),
                updatedAt.plusSeconds(30),
                0,
                status,
                Optional.empty(),
                trace(proof.traceId()),
                updatedAt);
        return new RouteAttemptControlRecord(new Revision(status == RouteAttemptLifecycleStatus.ACKED ? 5 : 3), 1L,
                Optional.of(snapshot));
    }

    private static TraceEnvelope trace(String traceId) {
        return new TraceEnvelope(
                traceId,
                "span-lobby-route",
                Optional.empty(),
                Instant.parse("2026-06-17T00:00:00Z"),
                "minecraft-status-client-test",
                new InstanceId("instance-minecraft-status-client-test"));
    }

    private static final class FakeMinecraftStatusServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Future<Void> server;

        private FakeMinecraftStatusServer(ServerSocket serverSocket, Future<Void> server) {
            this.serverSocket = serverSocket;
            this.server = server;
        }

        private static FakeMinecraftStatusServer start() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            var executor = Executors.newSingleThreadExecutor();
            Future<Void> server = executor.submit(new StatusExchange(serverSocket));
            executor.shutdown();
            return new FakeMinecraftStatusServer(serverSocket, server);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            server.get(2, TimeUnit.SECONDS);
        }
    }

    private static final class FakeMinecraftClusterServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Future<Void> server;

        private FakeMinecraftClusterServer(ServerSocket serverSocket, Future<Void> server) {
            this.serverSocket = serverSocket;
            this.server = server;
        }

        private static FakeMinecraftClusterServer start(ExchangeKind... exchanges) throws IOException {
            return startOnPort(0, exchanges);
        }

        private static FakeMinecraftClusterServer startOnPort(int port, ExchangeKind... exchanges) throws IOException {
            ServerSocket serverSocket = new ServerSocket(port, exchanges.length, InetAddress.getByName("127.0.0.1"));
            var executor = Executors.newSingleThreadExecutor();
            Future<Void> server = executor.submit(() -> {
                for (ExchangeKind exchange : exchanges) {
                    try (Socket socket = serverSocket.accept()) {
                        socket.setSoTimeout(2_000);
                        switch (exchange) {
                            case STATUS -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 0, 1);
                                verifyStatusRequest(readPacket(socket.getInputStream()));
                                writeStatusResponse(socket);
                            }
                            case LOGIN_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                            }
                            case LOBBY_PROOF_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_BEFORE_PLAY -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_WRONG_CHANNEL_THEN_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        24,
                                        "fulcrum:wrong_probe",
                                        new InstanceId("paper-instance-lobby-two"),
                                        new SessionId("session-lobby-other"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_WRONG_PACKET_THEN_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        25,
                                        PaperLobbyProofMessage.CHANNEL,
                                        new InstanceId("paper-instance-lobby-two"),
                                        new SessionId("session-lobby-other"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_SECOND_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotTwo", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotTwo");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo").value(),
                                        "Fulcrum Bot Two",
                                        "[Admin] Fulcrum Bot Two: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_SECOND_DIFFERENT_SESSION -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotTwo", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotTwo");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-two"),
                                        new SessionId("session-lobby-other"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo").value(),
                                        "Fulcrum Bot Two",
                                        "[Admin] Fulcrum Bot Two: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_SECOND_DIFFERENT_SLOT -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotTwo", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotTwo");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-one"),
                                        new SessionId("session-lobby-shared"),
                                        new SlotId("slot-lobby-other"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo").value(),
                                        "Fulcrum Bot Two",
                                        "[Admin] Fulcrum Bot Two: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_WRONG_SPAWN -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-one"),
                                        new SessionId("session-lobby-shared"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat",
                                        0,
                                        64,
                                        0,
                                        0.5D,
                                        66.0D,
                                        0.5D,
                                        0.0F,
                                        0.0F);
                            }
                            case LOBBY_PROOF_WRONG_MANIFEST -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-one"),
                                        new SessionId("session-lobby-shared"),
                                        new ResolvedManifestId("manifest-unexpected-lobby"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_WRONG_TRACE -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-one"),
                                        new SessionId("session-lobby-shared"),
                                        new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                                        "trace-unexpected-lobby",
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_WRONG_ROUTE -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-one"),
                                        new SessionId("session-lobby-shared"),
                                        new RouteId("route-unexpected-lobby"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOGIN_DENIED_NO_ROUTE -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotThree", readLoginStart(readPacket(socket.getInputStream())));
                                writeLoginDisconnect(socket, "{\"text\":\"No lobby route is currently available\"}");
                            }
                            case LOBBY_PROOF_SCALE_OUT_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotFour", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotFour");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-two"),
                                        new SessionId("session-lobby-scale-out"),
                                        new SlotId("slot-lobby-scale-out"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotFour").value(),
                                        "Fulcrum Bot Four",
                                        "[Admin] Fulcrum Bot Four: fulcrum-proof-chat");
                            }
                            case LOGIN_DENIED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("BannedFulcrumBot", readLoginStart(readPacket(socket.getInputStream())));
                                writeLoginDisconnect(socket, "{\"text\":\"Banned by Fulcrum\"}");
                            }
                        }
                    }
                }
                return null;
            });
            executor.shutdown();
            return new FakeMinecraftClusterServer(serverSocket, server);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            server.get(2, TimeUnit.SECONDS);
        }
    }

    private enum ExchangeKind {
        STATUS,
        LOGIN_ACCEPTED,
        LOBBY_PROOF_ACCEPTED,
        LOBBY_PROOF_BEFORE_PLAY,
        LOBBY_PROOF_WRONG_CHANNEL_THEN_ACCEPTED,
        LOBBY_PROOF_WRONG_PACKET_THEN_ACCEPTED,
        LOBBY_PROOF_SECOND_ACCEPTED,
        LOBBY_PROOF_SECOND_DIFFERENT_SESSION,
        LOBBY_PROOF_SECOND_DIFFERENT_SLOT,
        LOBBY_PROOF_WRONG_SPAWN,
        LOBBY_PROOF_WRONG_MANIFEST,
        LOBBY_PROOF_WRONG_TRACE,
        LOBBY_PROOF_WRONG_ROUTE,
        LOGIN_DENIED_NO_ROUTE,
        LOBBY_PROOF_SCALE_OUT_ACCEPTED,
        LOGIN_DENIED
    }

    private static final class StatusExchange implements Callable<Void> {
        private final ServerSocket serverSocket;

        private StatusExchange(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        @Override
        public Void call() throws Exception {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(2_000);
                verifyHandshake(readPacket(socket.getInputStream()), 0, 1);
                verifyStatusRequest(readPacket(socket.getInputStream()));
                writeStatusResponse(socket);
            }
            return null;
        }
    }

    private static void verifyHandshake(byte[] packet, int protocolVersion, int nextState) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(0, MinecraftStatusClient.readVarInt(input));
        assertEquals(protocolVersion, MinecraftStatusClient.readVarInt(input));
        assertEquals("127.0.0.1", readString(input));
        assertTrue(readUnsignedShort(input) > 0);
        assertEquals(nextState, MinecraftStatusClient.readVarInt(input));
    }

    private static void verifyStatusRequest(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(0, MinecraftStatusClient.readVarInt(input));
    }

    private static void writeStatusResponse(Socket socket) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(body::write, 0);
        writeString(body, """
                {"version":{"name":"Fulcrum Test Velocity","protocol":767},"players":{"max":100,"online":2},"description":{"text":"Fulcrum lobby"}}
                """.trim());
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(frame::write, body.size());
        frame.writeBytes(body.toByteArray());
        socket.getOutputStream().write(frame.toByteArray());
        socket.getOutputStream().flush();
    }

    private static String readLoginStart(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(0, MinecraftStatusClient.readVarInt(input));
        String username = readString(input);
        assertEquals(16, input.readNBytes(16).length);
        return username;
    }

    private static void writeCompressionThenLoginSuccess(Socket socket, String username) throws IOException {
        ByteArrayOutputStream compression = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(compression::write, 3);
        MinecraftStatusClient.writeVarInt(compression::write, 256);
        writePacket(socket, compression.toByteArray());

        ByteArrayOutputStream success = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(success::write, 2);
        success.write(new byte[16]);
        writeString(success, username);
        MinecraftStatusClient.writeVarInt(success::write, 0);
        writeCompressedPacket(socket, success.toByteArray());
    }

    private static void writeConfigurationFinish(Socket socket) throws IOException {
        ByteArrayOutputStream finish = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(finish::write, 3);
        writeCompressedPacket(socket, finish.toByteArray());
    }

    private static void verifyConfigurationFinish(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(3, MinecraftStatusClient.readVarInt(input));
    }

    private static void writeLobbyProof(
            Socket socket,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            RouteId routeId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                routeId,
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared",
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            SlotId slotId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                slotId,
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                subjectUuid,
                displayName,
                decoratedChat);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            SlotId slotId,
            ResolvedManifestId resolvedManifestId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                slotId,
                resolvedManifestId,
                "trace-paper-session-lobby-shared",
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            SlotId slotId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat,
            int bedrockBlockX,
            int bedrockBlockY,
            int bedrockBlockZ,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                routeId(subjectUuid),
                slotId,
                resolvedManifestId,
                traceId,
                subjectUuid,
                displayName,
                decoratedChat,
                bedrockBlockX,
                bedrockBlockY,
                bedrockBlockZ,
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                resolvedManifestId,
                traceId,
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                subjectUuid,
                displayName,
                decoratedChat);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                resolvedManifestId,
                "trace-paper-session-lobby-shared",
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat,
            int bedrockBlockX,
            int bedrockBlockY,
            int bedrockBlockZ,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                subjectUuid,
                displayName,
                decoratedChat,
                bedrockBlockX,
                bedrockBlockY,
                bedrockBlockZ,
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat,
            int bedrockBlockX,
            int bedrockBlockY,
            int bedrockBlockZ,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                resolvedManifestId,
                "trace-paper-session-lobby-shared",
                subjectUuid,
                displayName,
                decoratedChat,
                bedrockBlockX,
                bedrockBlockY,
                bedrockBlockZ,
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat,
            int bedrockBlockX,
            int bedrockBlockY,
            int bedrockBlockZ,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                routeId(subjectUuid),
                new SlotId("slot-lobby-shared"),
                resolvedManifestId,
                traceId,
                subjectUuid,
                displayName,
                decoratedChat,
                bedrockBlockX,
                bedrockBlockY,
                bedrockBlockZ,
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            RouteId routeId,
            SlotId slotId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat,
            int bedrockBlockX,
            int bedrockBlockY,
            int bedrockBlockZ,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch) throws IOException {
        writeLobbyProof(
                socket,
                24,
                PaperLobbyProofMessage.CHANNEL,
                instanceId,
                sessionId,
                routeId,
                slotId,
                resolvedManifestId,
                traceId,
                subjectUuid,
                displayName,
                decoratedChat,
                bedrockBlockX,
                bedrockBlockY,
                bedrockBlockZ,
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch);
    }

    private static void writeLobbyProof(
            Socket socket,
            int packetId,
            String channel,
            InstanceId instanceId,
            SessionId sessionId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                packetId,
                channel,
                instanceId,
                sessionId,
                routeId(subjectUuid),
                new SlotId("slot-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-paper-session-lobby-shared",
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            int packetId,
            String channel,
            InstanceId instanceId,
            SessionId sessionId,
            RouteId routeId,
            SlotId slotId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat,
            int bedrockBlockX,
            int bedrockBlockY,
            int bedrockBlockZ,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch) throws IOException {
        PaperLobbyProofMessage proof = new PaperLobbyProofMessage(
                instanceId,
                sessionId,
                routeId,
                slotId,
                resolvedManifestId,
                traceId,
                new SubjectId(subjectUuid),
                "world",
                bedrockBlockX,
                bedrockBlockY,
                bedrockBlockZ,
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch,
                displayName,
                Optional.of("Admin"),
                decoratedChat);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(packet::write, packetId);
        writeString(packet, channel);
        packet.writeBytes(proof.encode());
        writeCompressedPacket(socket, packet.toByteArray());
    }

    private static void writeLoginDisconnect(Socket socket, String reason) throws IOException {
        ByteArrayOutputStream disconnect = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(disconnect::write, 0);
        writeString(disconnect, reason);
        writePacket(socket, disconnect.toByteArray());
    }

    private static void writePacket(Socket socket, byte[] body) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(frame::write, body.length);
        frame.writeBytes(body);
        socket.getOutputStream().write(frame.toByteArray());
        socket.getOutputStream().flush();
    }

    private static void writeCompressedPacket(Socket socket, byte[] body) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(payload::write, 0);
        payload.writeBytes(body);
        writePacket(socket, payload.toByteArray());
    }

    private static byte[] readPacket(InputStream input) throws IOException {
        int length = MinecraftStatusClient.readVarInt(input);
        byte[] packet = input.readNBytes(length);
        if (packet.length != length) {
            throw new EOFException("Expected " + length + " bytes, got " + packet.length);
        }
        return packet;
    }

    private static byte[] readCompressedPacket(InputStream input) throws IOException {
        ByteArrayInputStream packet = new ByteArrayInputStream(readPacket(input));
        assertEquals(0, MinecraftStatusClient.readVarInt(packet));
        return packet.readAllBytes();
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftStatusClient.writeVarInt(output::write, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readString(InputStream input) throws IOException {
        int length = MinecraftStatusClient.readVarInt(input);
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static int readUnsignedShort(InputStream input) throws IOException {
        int high = input.read();
        int low = input.read();
        if (high < 0 || low < 0) {
            throw new EOFException("Expected unsigned short");
        }
        return (high << 8) | low;
    }

    private static int unusedLocalPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return serverSocket.getLocalPort();
        }
    }

    private static RouteId routeId(UUID subjectUuid) {
        return new RouteId("route-velocity-login-" + subjectUuid.toString().replace("-", ""));
    }

    private static PresenceId velocityPresenceId(SubjectId subjectId) {
        return new PresenceId("presence-velocity-login-" + subjectId.value().toString().replace("-", ""));
    }

    private static String compact(String value) {
        return value.replace("-", "");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static String shortSubject(SubjectId subjectId) {
        return subjectId.value().toString().replace("-", "").substring(0, 16);
    }
}
