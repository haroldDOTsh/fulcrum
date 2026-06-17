package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LobbySharedShardAllocationProvisionerTest {
    @Test
    void publishesTypedSharedShardAllocationCommand() throws Exception {
        LobbySharedShardAllocationProvisioner.Config config = config();
        MockProducer<String, String> producer = producer();

        LobbySharedShardAllocationProvisioner.Result result =
                LobbySharedShardAllocationProvisioner.provision(config, producer);

        assertEquals(1, result.publishedCommandCount());
        assertEquals("ctrl.cmd.shared-shard-allocation", result.topic());
        assertEquals("session-lobby-shared", result.key());

        ProducerRecord<String, String> record = producer.history().getFirst();
        assertEquals("ctrl.cmd.shared-shard-allocation", record.topic());
        assertEquals("session-lobby-shared", record.key());

        SharedShardAllocationRequest decoded =
                LobbySharedShardAllocationProvisioner.decodePublishedCommand(record);
        assertEquals(new ExperienceId("experience-lobby"), decoded.experienceId());
        assertEquals(new PoolId("pool-lobby"), decoded.poolId());
        assertEquals(new SessionId("session-lobby-shared"), decoded.sessionId());
        assertEquals(new ResolvedManifestId("manifest-lobby-bedrock-v1"), decoded.resolvedManifestId());
        assertEquals("trace-test-allocation", decoded.traceEnvelope().traceId());
        assertEquals("span-test-allocation", decoded.traceEnvelope().spanId());
        assertEquals(new InstanceId("instance-test-allocation"), decoded.traceEnvelope().originInstanceId());
        assertEquals("lobby-shared-shard-allocation-provisioner", decoded.traceEnvelope().originService());
        assertEquals(Instant.parse("2026-06-17T12:00:00Z"), decoded.requestedAt());
    }

    @Test
    void configReadsRequiredBootstrapAndDeterministicDefaults() {
        LobbySharedShardAllocationProvisioner.Config config =
                LobbySharedShardAllocationProvisioner.Config.fromEnvironment(RuntimeEnvironment.of(Map.of(
                        "FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")));

        assertEquals("kafka:9092", config.kafkaBootstrapServers());
        assertEquals("ctrl.cmd.shared-shard-allocation", config.commandTopic());
        assertEquals(new ExperienceId("experience-lobby"), config.experienceId());
        assertEquals(new PoolId("pool-lobby"), config.poolId());
        assertEquals(new SessionId("session-lobby-shared"), config.sessionId());
        assertEquals(new ResolvedManifestId("manifest-lobby-bedrock-v1"), config.resolvedManifestId());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), config.requestedAt());
    }

    private static LobbySharedShardAllocationProvisioner.Config config() {
        return new LobbySharedShardAllocationProvisioner.Config(
                "unused:9092",
                "ctrl.cmd.shared-shard-allocation",
                new ExperienceId("experience-lobby"),
                new PoolId("pool-lobby"),
                new SessionId("session-lobby-shared"),
                new ResolvedManifestId("manifest-lobby-bedrock-v1"),
                "trace-test-allocation",
                "span-test-allocation",
                new InstanceId("instance-test-allocation"),
                Instant.parse("2026-06-17T12:00:00Z"));
    }

    private static MockProducer<String, String> producer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }
}
