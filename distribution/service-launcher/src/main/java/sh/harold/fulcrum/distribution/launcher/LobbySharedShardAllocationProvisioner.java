package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.control.allocation.SharedShardAllocationRequest;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

public final class LobbySharedShardAllocationProvisioner {
    static final String DEFAULT_COMMAND_TOPIC = "ctrl.cmd.shared-shard-allocation";
    static final String DEFAULT_EXPERIENCE_ID = "experience-lobby";
    static final String DEFAULT_POOL_ID = "pool-lobby";
    static final String DEFAULT_SESSION_ID = "session-lobby-shared";
    static final String DEFAULT_RESOLVED_MANIFEST_ID = "manifest-lobby-bedrock-v1";
    static final Instant DEFAULT_REQUESTED_AT = Instant.parse("2026-01-01T00:00:00Z");
    static final String DEFAULT_TRACE_ID = "trace-lobby-shared-allocation";
    static final String DEFAULT_SPAN_ID = "span-lobby-shared-allocation";
    static final String DEFAULT_INSTANCE_ID = "instance-lobby-shared-shard-allocation";
    static final String ORIGIN_SERVICE = "lobby-shared-shard-allocation-provisioner";

    private LobbySharedShardAllocationProvisioner() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("LobbySharedShardAllocationProvisioner reads configuration from environment");
        }
        Config config = Config.fromEnvironment(RuntimeEnvironment.system());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(config.kafkaBootstrapServers()))) {
            Result result = provision(config, producer);
            System.out.println("publishedSharedShardAllocationCommands=" + result.publishedCommandCount());
            System.out.println("sharedShardAllocationTopic=" + result.topic());
            System.out.println("sharedShardAllocationSessionId=" + result.request().sessionId().value());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed to publish lobby shared-shard allocation command", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("failed to publish lobby shared-shard allocation command", exception);
        }
    }

    static Result provision(
            Config config,
            Producer<String, String> producer) throws ExecutionException, InterruptedException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(producer, "producer");

        SharedShardAllocationRequest request = config.request();
        String payload = ControlCommandWireCodec.encodeSharedShardAllocationRequest(request);
        producer.send(new ProducerRecord<>(
                config.commandTopic(),
                request.sessionId().value(),
                payload)).get();
        producer.flush();
        return new Result(config.commandTopic(), request.sessionId().value(), request, payload, 1);
    }

    static SharedShardAllocationRequest decodePublishedCommand(ProducerRecord<String, String> record) {
        Objects.requireNonNull(record, "record");
        return ControlCommandWireCodec.decodeSharedShardAllocationRequest(
                new ConsumerRecord<>(record.topic(), 0, 0L, record.key(), record.value()));
    }

    private static Properties producerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return properties;
    }

    record Config(
            String kafkaBootstrapServers,
            String commandTopic,
            ExperienceId experienceId,
            PoolId poolId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            String spanId,
            InstanceId instanceId,
            Instant requestedAt) {
        Config {
            kafkaBootstrapServers = requireNonBlank(kafkaBootstrapServers, "kafkaBootstrapServers");
            commandTopic = requireNonBlank(commandTopic, "commandTopic");
            experienceId = Objects.requireNonNull(experienceId, "experienceId");
            poolId = Objects.requireNonNull(poolId, "poolId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            traceId = requireNonBlank(traceId, "traceId");
            spanId = requireNonBlank(spanId, "spanId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        }

        static Config fromEnvironment(RuntimeEnvironment environment) {
            Objects.requireNonNull(environment, "environment");
            return new Config(
                    required(environment, "FULCRUM_KAFKA_BOOTSTRAP_SERVERS"),
                    environment.value("FULCRUM_SHARED_SHARD_ALLOCATION_COMMAND_TOPIC").orElse(DEFAULT_COMMAND_TOPIC),
                    new ExperienceId(environment.value("FULCRUM_LOBBY_EXPERIENCE_ID").orElse(DEFAULT_EXPERIENCE_ID)),
                    new PoolId(environment.value("FULCRUM_LOBBY_POOL_ID").orElse(DEFAULT_POOL_ID)),
                    new SessionId(environment.value("FULCRUM_LOBBY_SESSION_ID").orElse(DEFAULT_SESSION_ID)),
                    new ResolvedManifestId(environment.value("FULCRUM_LOBBY_RESOLVED_MANIFEST_ID")
                            .orElse(DEFAULT_RESOLVED_MANIFEST_ID)),
                    environment.value("FULCRUM_LOBBY_ALLOCATION_TRACE_ID").orElse(DEFAULT_TRACE_ID),
                    environment.value("FULCRUM_LOBBY_ALLOCATION_SPAN_ID").orElse(DEFAULT_SPAN_ID),
                    new InstanceId(environment.value("FULCRUM_INSTANCE_ID").orElse(DEFAULT_INSTANCE_ID)),
                    environment.value("FULCRUM_LOBBY_ALLOCATION_REQUESTED_AT")
                            .map(Instant::parse)
                            .orElse(DEFAULT_REQUESTED_AT));
        }

        SharedShardAllocationRequest request() {
            return new SharedShardAllocationRequest(
                    experienceId,
                    poolId,
                    sessionId,
                    resolvedManifestId,
                    new TraceEnvelope(
                            traceId,
                            spanId,
                            Optional.empty(),
                            requestedAt,
                            ORIGIN_SERVICE,
                            instanceId),
                    requestedAt);
        }
    }

    record Result(
            String topic,
            String key,
            SharedShardAllocationRequest request,
            String payload,
            int publishedCommandCount) {
        Result {
            topic = requireNonBlank(topic, "topic");
            key = requireNonBlank(key, "key");
            request = Objects.requireNonNull(request, "request");
            payload = requireNonBlank(payload, "payload");
            if (publishedCommandCount < 1) {
                throw new IllegalArgumentException("publishedCommandCount must be positive");
            }
        }
    }

    private static String required(RuntimeEnvironment environment, String name) {
        return environment.value(name)
                .orElseThrow(() -> new IllegalArgumentException("Missing required environment variable " + name));
    }

    private static String requireNonBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
