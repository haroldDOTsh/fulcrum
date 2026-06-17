package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import sh.harold.fulcrum.adapters.objectstorage.ObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.StoredObject;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.data.artifact.ArtifactDigest;
import sh.harold.fulcrum.data.artifact.ArtifactKind;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataAuthority;
import sh.harold.fulcrum.data.artifact.ContentAddress;
import sh.harold.fulcrum.data.artifact.ProvenanceRef;
import sh.harold.fulcrum.data.artifact.PublishArtifactMetadata;
import sh.harold.fulcrum.data.authority.AuthorityCommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LobbyWorldArtifactProvisioner {
    static final String DEFAULT_BUCKET = "artifact-store";
    static final String DEFAULT_ARTIFACT_ID = "artifact-lobby-bedrock-world";
    static final String DEFAULT_COMPATIBILITY = "lobby-world-v1";
    static final String DEFAULT_PROVENANCE = "fulcrum:lobby-bedrock-world:v1";
    static final String DEFAULT_COMMAND_TOPIC = "cmd.artifact-metadata";
    static final String DEFAULT_PRINCIPAL_ID = "principal-lobby-world-provisioner";
    static final String DEFAULT_INSTANCE_ID = "instance-lobby-world-provisioner";

    private LobbyWorldArtifactProvisioner() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("LobbyWorldArtifactProvisioner reads configuration from environment");
        }
        RuntimeEnvironment environment = RuntimeEnvironment.system();
        Config config = Config.fromEnvironment(environment);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(config.kafkaBootstrapServers()))) {
            Result result = provision(
                    config,
                    RuntimeExternalClients.objectStorage(config.objectStore(), config.objectBucket()),
                    producer,
                    Clock.systemUTC());
            System.out.println("publishedArtifact=" + result.artifactPin().artifactId().value());
            System.out.println("digest=" + result.artifactDigest().algorithm() + ":" + result.artifactDigest().value());
            System.out.println("contentAddress=" + result.contentAddress().value());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed to provision lobby world artifact", exception);
        } catch (IOException | ExecutionException exception) {
            throw new IllegalStateException("failed to provision lobby world artifact", exception);
        }
    }

    public static String defaultArchiveDigest() {
        try {
            return archiveDigest(new ArtifactId(DEFAULT_ARTIFACT_ID), DEFAULT_COMPATIBILITY);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to build default lobby world artifact archive", exception);
        }
    }

    static Result provision(
            Config config,
            ObjectStorageAdapter objectStorage,
            Producer<String, String> producer,
            Clock clock) throws IOException, ExecutionException, InterruptedException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(objectStorage, "objectStorage");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(clock, "clock");

        byte[] archive = LobbyBedrockWorldArchive.create(config.artifactId(), config.compatibility());
        String digestValue = sha256(archive);
        ArtifactPin artifactPin = new ArtifactPin(config.artifactId(), digestValue, config.compatibility());
        StoredObject stored = objectStorage.put(artifactPin, archive);
        ArtifactDigest artifactDigest = new ArtifactDigest(stored.digest().algorithm(), stored.digest().value());
        PublishArtifactMetadata payload = new PublishArtifactMetadata(
                artifactDigest,
                ArtifactKind.MAP_TEMPLATE_ARTIFACT,
                stored.byteLength(),
                new ContentAddress(stored.address().value()),
                new ProvenanceRef(config.provenance()));
        AuthorityCommand<PublishArtifactMetadata> command = publishCommand(config, payload, clock.instant());
        String encoded = ArtifactMetadataAuthorityWireCodec.encodeCommand(command);
        producer.send(new ProducerRecord<>(
                config.commandTopic(),
                command.envelope().aggregateId().value(),
                encoded)).get();
        return new Result(artifactPin, artifactDigest, stored.address(), stored.byteLength(), command);
    }

    private static AuthorityCommand<PublishArtifactMetadata> publishCommand(
            Config config,
            PublishArtifactMetadata payload,
            Instant receivedAt) {
        String digestValue = payload.digest().value();
        String commandSuffix = digestValue.substring(0, 16);
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-lobby-world-artifact-" + commandSuffix),
                        new IdempotencyKey("idem-lobby-world-artifact-" + commandSuffix),
                        config.principalId(),
                        ArtifactMetadataAuthority.aggregateId(payload.digest()),
                        new ContractName(ArtifactMetadataAuthorityWireCodec.CONTRACT),
                        new CommandName(ArtifactMetadataAuthorityWireCodec.PUBLISH_COMMAND),
                        new TraceEnvelope(
                                "trace-lobby-world-artifact-" + commandSuffix,
                                "span-lobby-world-artifact-" + commandSuffix,
                                Optional.empty(),
                                receivedAt,
                                "content-provisioner",
                                config.instanceId()),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                config.principalId(),
                1,
                Optional.of(new Revision(0)),
                payloadFingerprint(payload),
                receivedAt);
    }

    private static String payloadFingerprint(PublishArtifactMetadata payload) {
        String canonical = String.join("|",
                ArtifactMetadataAuthorityWireCodec.CONTRACT,
                ArtifactMetadataAuthorityWireCodec.PUBLISH_COMMAND,
                payload.digest().algorithm(),
                payload.digest().value(),
                payload.kind().name(),
                Long.toString(payload.byteLength()),
                payload.contentAddress().value(),
                payload.provenance().value());
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String archiveDigest(ArtifactId artifactId, String compatibility) throws IOException {
        return sha256(LobbyBedrockWorldArchive.create(artifactId, compatibility));
    }

    private static Properties producerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return properties;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    record Config(
            RuntimeConnectionSettings.ObjectStoreConnection objectStore,
            String objectBucket,
            ArtifactId artifactId,
            String compatibility,
            String provenance,
            String kafkaBootstrapServers,
            String commandTopic,
            PrincipalId principalId,
            InstanceId instanceId) {
        Config {
            objectStore = Objects.requireNonNull(objectStore, "objectStore");
            objectBucket = requireNonBlank(objectBucket, "objectBucket");
            artifactId = Objects.requireNonNull(artifactId, "artifactId");
            compatibility = requireNonBlank(compatibility, "compatibility");
            provenance = requireNonBlank(provenance, "provenance");
            kafkaBootstrapServers = requireNonBlank(kafkaBootstrapServers, "kafkaBootstrapServers");
            commandTopic = requireNonBlank(commandTopic, "commandTopic");
            principalId = Objects.requireNonNull(principalId, "principalId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
        }

        static Config fromEnvironment(RuntimeEnvironment environment) {
            Objects.requireNonNull(environment, "environment");
            return new Config(
                    RuntimeConnectionSettings.objectStore(environment),
                    environment.value("FULCRUM_LOBBY_WORLD_OBJECT_BUCKET").orElse(DEFAULT_BUCKET),
                    new ArtifactId(environment.value("FULCRUM_LOBBY_WORLD_ARTIFACT_ID").orElse(DEFAULT_ARTIFACT_ID)),
                    environment.value("FULCRUM_LOBBY_WORLD_ARTIFACT_COMPATIBILITY").orElse(DEFAULT_COMPATIBILITY),
                    environment.value("FULCRUM_LOBBY_WORLD_PROVENANCE").orElse(DEFAULT_PROVENANCE),
                    required(environment, "FULCRUM_KAFKA_BOOTSTRAP_SERVERS"),
                    environment.value("FULCRUM_ARTIFACT_METADATA_COMMAND_TOPIC").orElse(DEFAULT_COMMAND_TOPIC),
                    new PrincipalId(environment.value("FULCRUM_PRINCIPAL_ID").orElse(DEFAULT_PRINCIPAL_ID)),
                    new InstanceId(environment.value("FULCRUM_INSTANCE_ID").orElse(DEFAULT_INSTANCE_ID)));
        }

        Path objectStoreRoot() {
            return objectStore.localRoot().orElseThrow(() -> new RuntimeConfigurationException(
                    "LobbyWorldArtifactProvisioner objectStoreRoot is only available for local object storage"));
        }
    }

    record Result(
            ArtifactPin artifactPin,
            ArtifactDigest artifactDigest,
            ArtifactObjectAddress contentAddress,
            long byteLength,
            AuthorityCommand<PublishArtifactMetadata> command) {
        Result {
            artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
            artifactDigest = Objects.requireNonNull(artifactDigest, "artifactDigest");
            contentAddress = Objects.requireNonNull(contentAddress, "contentAddress");
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
            command = Objects.requireNonNull(command, "command");
        }
    }

    private static final class LobbyBedrockWorldArchive {
        private static final long ZIP_EPOCH = 0L;

        private LobbyBedrockWorldArchive() {
        }

        static byte[] create(ArtifactId artifactId, String compatibility) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                put(zip, "fulcrum-lobby-world.properties", String.join("\n",
                        "artifactId=" + artifactId.value(),
                        "compatibility=" + compatibility,
                        "template=lobby-bedrock-bootstrap",
                        "spawnBlock=bedrock",
                        "spawnX=0",
                        "spawnY=64",
                        "spawnZ=0",
                        ""));
            }
            return bytes.toByteArray();
        }

        private static void put(ZipOutputStream zip, String path, String value) throws IOException {
            byte[] payload = value.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(payload);
            ZipEntry entry = new ZipEntry(path);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            entry.setTime(ZIP_EPOCH);
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.closeEntry();
        }
    }

    private static String required(RuntimeEnvironment environment, String name) {
        return environment.value(name)
                .orElseThrow(() -> new RuntimeConfigurationException("Missing required environment binding: " + name));
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
