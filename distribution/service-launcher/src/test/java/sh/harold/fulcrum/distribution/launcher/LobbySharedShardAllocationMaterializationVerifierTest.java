package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbySharedShardAllocationMaterializationVerifierTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void configMatchesProvisionerDefaultsAndStateTopic() {
        LobbySharedShardAllocationMaterializationVerifier.Config config =
                LobbySharedShardAllocationMaterializationVerifier.Config.fromEnvironment(RuntimeEnvironment.of(Map.of(
                        "FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
                        "FULCRUM_INSTANCE_ID", "allocation-verifier-test")));

        assertEquals("kafka:9092", config.kafkaBootstrapServers());
        assertEquals("ctrl.state.shared-shard-allocation", config.stateTopic());
        assertEquals("fulcrum-lobby-shared-shard-allocation-materialization-allocation-verifier-test",
                config.consumerGroup());
        assertEquals(new ExperienceId("experience-lobby"), config.experienceId());
        assertEquals(new PoolId("pool-lobby"), config.poolId());
        assertEquals(new SessionId("session-lobby-shared"), config.sessionId());
        assertEquals(new ResolvedManifestId("manifest-lobby-bedrock-v1"), config.resolvedManifestId());
        assertEquals(Duration.ofSeconds(180), config.timeout());
        assertEquals(Duration.ofSeconds(1), config.pollInterval());
    }

    @Test
    void findsExpectedSharedShardAllocationState() {
        LobbySharedShardAllocationMaterializationVerifier.Config config = config();
        String state = state("session-lobby-shared", "manifest-lobby-bedrock-v1");

        Optional<LobbySharedShardAllocationMaterializationVerifier.MatchedState> match =
                LobbySharedShardAllocationMaterializationVerifier.matchingState(config, List.of(
                        new ConsumerRecord<>("ctrl.state.shared-shard-allocation", 0, 0L, "other", "recordType=other"),
                        new ConsumerRecord<>("ctrl.state.shared-shard-allocation", 0, 1L,
                                "ctrl.state.shared-shard-allocation:session-lobby-shared",
                                state)));

        assertTrue(match.isPresent());
        assertEquals(2, match.orElseThrow().recordsScanned());
        assertEquals(new SessionId("session-lobby-shared"),
                match.orElseThrow().allocation().request().sessionId());
        assertEquals(new HostNetworkEndpoint("10.0.0.25", 25565),
                match.orElseThrow().allocation().claim().minecraftEndpoint());
    }

    @Test
    void ignoresAllocationStateForDifferentSession() {
        Optional<ExternalControllerWorkerCatalog.StoredSharedShardAllocation> match =
                LobbySharedShardAllocationMaterializationVerifier.decodeMatchingState(
                        config(),
                        state("session-other", "manifest-lobby-bedrock-v1"));

        assertTrue(match.isEmpty());
    }

    private static LobbySharedShardAllocationMaterializationVerifier.Config config() {
        return new LobbySharedShardAllocationMaterializationVerifier.Config(
                "unused:9092",
                "ctrl.state.shared-shard-allocation",
                "test-group",
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                new SessionId("session-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                Duration.ofSeconds(5),
                Duration.ofMillis(100));
    }

    private static String state(String sessionId, String resolvedManifestId) {
        SharedShardAllocationRequest request = new SharedShardAllocationRequest(
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                new SessionId(sessionId),
                new ResolvedManifestId(resolvedManifestId),
                trace("request"),
                NOW);
        HostAllocationClaim claim = new HostAllocationClaim(
                new SlotId("slot-lobby-shared"),
                new SessionId(sessionId),
                new HostInstanceIdentity(
                        new InstanceId("paper-lobby-0"),
                        "paper",
                        new PoolId("pool-lobby"),
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-fulcrum-paper-agent")),
                new ResolvedManifestId(resolvedManifestId),
                new HostNetworkEndpoint("10.0.0.25", 25565),
                trace("claim"),
                NOW.plusSeconds(1));
        return ControllerStateWireCodec.encodeSharedShardAllocation(
                new ExternalControllerWorkerCatalog.StoredSharedShardAllocation(
                        "experience-lobby|pool-lobby|" + sessionId + "|" + resolvedManifestId,
                        request,
                        claim));
    }

    private static TraceEnvelope trace(String suffix) {
        return new TraceEnvelope(
                "trace-" + suffix,
                "span-" + suffix,
                Optional.empty(),
                NOW,
                "test",
                new InstanceId("instance-test"));
    }
}
