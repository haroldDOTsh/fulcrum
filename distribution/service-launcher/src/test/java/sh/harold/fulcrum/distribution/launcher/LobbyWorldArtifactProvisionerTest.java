package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.artifact.ArtifactKind;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.artifact.PublishArtifactMetadata;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbyWorldArtifactProvisionerTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("principal-test-provisioner");
    private static final InstanceId INSTANCE = new InstanceId("instance-test-provisioner");

    @Test
    void storesLobbyWorldArchiveAndPublishesArtifactMetadataCommand(@TempDir Path tempDir) throws Exception {
        LobbyWorldArtifactProvisioner.Config config = config(tempDir);
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                config.objectStoreRoot(),
                config.objectBucket());
        MockProducer<String, String> producer = producer();

        LobbyWorldArtifactProvisioner.Result result = LobbyWorldArtifactProvisioner.provision(
                config,
                objectStorage,
                producer,
                Clock.fixed(NOW, ZoneOffset.UTC));

        byte[] archive = objectStorage.read(result.contentAddress()).orElseThrow();
        assertEquals(result.byteLength(), archive.length);
        assertEquals(result.artifactPin().digest(), result.artifactDigest().value());
        assertEquals(LobbyWorldArtifactProvisioner.defaultArchiveDigest(), result.artifactDigest().value());
        assertArchiveMetadata(archive, result.artifactPin().artifactId().value(), result.artifactPin().compatibility());
        ProducerRecord<String, String> record = producer.history().getFirst();
        assertEquals("cmd.artifact-metadata", record.topic());
        assertEquals(result.command().envelope().aggregateId().value(), record.key());

        AuthorityCommand<PublishArtifactMetadata> decoded = ArtifactMetadataAuthorityWireCodec.decodeCommand(
                new ConsumerRecord<>(record.topic(), 0, 0L, record.key(), record.value()));
        assertEquals(result.command().envelope().commandId(), decoded.envelope().commandId());
        assertEquals(result.command().envelope().idempotencyKey(), decoded.envelope().idempotencyKey());
        assertEquals(PRINCIPAL, decoded.authenticatedPrincipal());
        assertEquals(1, decoded.fencingEpoch());
        assertEquals(new Revision(0), decoded.expectedRevision().orElseThrow());
        assertEquals(result.artifactDigest(), decoded.envelope().payload().digest());
        assertEquals(ArtifactKind.MAP_TEMPLATE_ARTIFACT, decoded.envelope().payload().kind());
        assertEquals(result.byteLength(), decoded.envelope().payload().byteLength());
        assertEquals(result.contentAddress().value(), decoded.envelope().payload().contentAddress().value());
        assertEquals("test:lobby-world", decoded.envelope().payload().provenance().value());
    }

    @Test
    void provisioningIsDeterministicForRepeatedRuns(@TempDir Path tempDir) throws Exception {
        LobbyWorldArtifactProvisioner.Config config = config(tempDir);
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                config.objectStoreRoot(),
                config.objectBucket());

        LobbyWorldArtifactProvisioner.Result first = LobbyWorldArtifactProvisioner.provision(
                config,
                objectStorage,
                producer(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        LobbyWorldArtifactProvisioner.Result second = LobbyWorldArtifactProvisioner.provision(
                config,
                objectStorage,
                producer(),
                Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));

        assertEquals(first.artifactPin(), second.artifactPin());
        assertEquals(first.contentAddress(), second.contentAddress());
        assertEquals(first.command().envelope().commandId(), second.command().envelope().commandId());
        assertEquals(first.command().envelope().idempotencyKey(), second.command().envelope().idempotencyKey());
        assertEquals(first.command().payloadFingerprint(), second.command().payloadFingerprint());
    }

    @Test
    void configReadsRequiredBindingsAndDefaults(@TempDir Path tempDir) {
        LobbyWorldArtifactProvisioner.Config config = LobbyWorldArtifactProvisioner.Config.fromEnvironment(
                RuntimeEnvironment.of(Map.of(
                        "FULCRUM_OBJECT_STORE_ROOT", tempDir.toString(),
                        "FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")));

        assertEquals(tempDir.toAbsolutePath().normalize(), config.objectStoreRoot());
        assertEquals("artifact-store", config.objectBucket());
        assertEquals(new ArtifactId("artifact-lobby-bedrock-world"), config.artifactId());
        assertEquals("lobby-world-v1", config.compatibility());
        assertEquals("fulcrum:lobby-bedrock-world:v1", config.provenance());
        assertEquals("kafka:9092", config.kafkaBootstrapServers());
        assertEquals("cmd.artifact-metadata", config.commandTopic());
    }

    @Test
    void configReadsS3ObjectStorageBindings() {
        LobbyWorldArtifactProvisioner.Config config = LobbyWorldArtifactProvisioner.Config.fromEnvironment(
                RuntimeEnvironment.of(Map.of(
                        "FULCRUM_OBJECT_STORE_MODE", "s3",
                        "FULCRUM_OBJECT_STORE_ENDPOINT", "http://minio.fulcrum-lobby:9000",
                        "FULCRUM_OBJECT_STORE_ACCESS_KEY", "fulcrum-access",
                        "FULCRUM_OBJECT_STORE_SECRET_KEY", "fulcrum-secret",
                        "FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")));

        assertEquals(RuntimeConnectionSettings.ObjectStoreMode.S3, config.objectStore().mode());
        RuntimeConnectionSettings.S3ObjectStoreConnection s3 = config.objectStore().s3().orElseThrow();
        assertEquals(java.net.URI.create("http://minio.fulcrum-lobby:9000"), s3.endpoint());
        assertEquals("us-east-1", s3.region());
    }

    private static LobbyWorldArtifactProvisioner.Config config(Path tempDir) {
        return new LobbyWorldArtifactProvisioner.Config(
                RuntimeConnectionSettings.ObjectStoreConnection.local(tempDir.resolve("object-store")),
                "artifact-store",
                new ArtifactId("artifact-lobby-bedrock-world"),
                "lobby-world-v1",
                "test:lobby-world",
                "unused:9092",
                "cmd.artifact-metadata",
                PRINCIPAL,
                INSTANCE);
    }

    private static MockProducer<String, String> producer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }

    private static void assertArchiveMetadata(byte[] archive, String artifactId, String compatibility) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }
        assertEquals(List.of("fulcrum-lobby-world.properties"), List.copyOf(entries.keySet()));
        assertFalse(entries.containsKey("level.dat"));
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(entries.get("fulcrum-lobby-world.properties")));
        assertEquals(artifactId, properties.getProperty("artifactId"));
        assertEquals(compatibility, properties.getProperty("compatibility"));
        assertEquals("lobby-bedrock-bootstrap", properties.getProperty("template"));
        assertEquals("bedrock", properties.getProperty("spawnBlock"));
        assertTrue(Integer.parseInt(properties.getProperty("spawnY")) >= 64);
    }
}
