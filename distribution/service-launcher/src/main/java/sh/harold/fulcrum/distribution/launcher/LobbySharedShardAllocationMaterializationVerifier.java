package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

public final class LobbySharedShardAllocationMaterializationVerifier {
    static final String DEFAULT_STATE_TOPIC = "ctrl.state.shared-shard-allocation";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    static final String DEFAULT_CONSUMER_GROUP = "fulcrum-lobby-shared-shard-allocation-materialization";

    private LobbySharedShardAllocationMaterializationVerifier() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 0) {
            throw new IllegalArgumentException(
                    "LobbySharedShardAllocationMaterializationVerifier reads configuration from environment");
        }
        Config config = Config.fromEnvironment(RuntimeEnvironment.system());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(config))) {
            Result result = verify(config, consumer, Clock.systemUTC());
            System.out.println("materializedSharedShardAllocationSessionId="
                    + result.allocation().request().sessionId().value());
            System.out.println("materializedSharedShardAllocationInstanceId="
                    + result.allocation().claim().instanceIdentity().instanceId().value());
            System.out.println("materializedSharedShardAllocationMinecraftEndpoint="
                    + result.allocation().claim().minecraftEndpoint().host()
                    + ":" + result.allocation().claim().minecraftEndpoint().port());
            System.out.println("materializedSharedShardAllocationRecordsScanned=" + result.recordsScanned());
        }
    }

    static Result verify(
            Config config,
            Consumer<String, String> consumer,
            Clock clock) throws InterruptedException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(clock, "clock");

        consumer.subscribe(List.of(config.stateTopic()));
        Instant deadline = clock.instant().plus(config.timeout());
        int recordsScanned = 0;
        while (clock.instant().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(config.pollInterval());
            recordsScanned += records.count();
            Optional<ExternalControllerWorkerCatalog.StoredSharedShardAllocation> allocation =
                    matchingState(config, records).map(MatchedState::allocation);
            if (allocation.isPresent()) {
                return new Result(allocation.orElseThrow(), recordsScanned);
            }
            Thread.sleep(Math.max(1L, config.pollInterval().toMillis()));
        }
        throw new IllegalStateException("lobby shared-shard allocation state did not materialize within "
                + config.timeout()
                + " on " + config.stateTopic()
                + " for Session " + config.sessionId().value());
    }

    static Optional<MatchedState> matchingState(
            Config config,
            Iterable<ConsumerRecord<String, String>> records) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(records, "records");
        int scanned = 0;
        for (ConsumerRecord<String, String> record : records) {
            scanned++;
            Optional<ExternalControllerWorkerCatalog.StoredSharedShardAllocation> allocation =
                    decodeMatchingState(config, record.value());
            if (allocation.isPresent()) {
                return Optional.of(new MatchedState(allocation.orElseThrow(), scanned));
            }
        }
        return Optional.empty();
    }

    static Optional<ExternalControllerWorkerCatalog.StoredSharedShardAllocation> decodeMatchingState(
            Config config,
            String payload) {
        Objects.requireNonNull(config, "config");
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        if (!ControllerStateWireCodec.isRecordType(payload, ControllerWorkerCatalog.SHARED_SHARD_ALLOCATION)) {
            return Optional.empty();
        }
        ExternalControllerWorkerCatalog.StoredSharedShardAllocation allocation =
                ControllerStateWireCodec.decodeSharedShardAllocation(payload);
        if (!allocation.request().experienceId().equals(config.experienceId())) {
            return Optional.empty();
        }
        if (!allocation.request().poolId().equals(config.poolId())) {
            return Optional.empty();
        }
        if (!allocation.request().sessionId().equals(config.sessionId())) {
            return Optional.empty();
        }
        if (!allocation.request().resolvedManifestId().equals(config.resolvedManifestId())) {
            return Optional.empty();
        }
        if (!allocation.claim().sessionId().equals(config.sessionId())) {
            return Optional.empty();
        }
        if (!allocation.claim().resolvedManifestId().equals(config.resolvedManifestId())) {
            return Optional.empty();
        }
        return Optional.of(allocation);
    }

    private static Properties consumerProperties(Config config) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, config.consumerGroup());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return properties;
    }

    record Config(
            String kafkaBootstrapServers,
            String stateTopic,
            String consumerGroup,
            ExperienceId experienceId,
            PoolId poolId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            Duration timeout,
            Duration pollInterval) {
        Config {
            kafkaBootstrapServers = requireNonBlank(kafkaBootstrapServers, "kafkaBootstrapServers");
            stateTopic = requireNonBlank(stateTopic, "stateTopic");
            consumerGroup = requireNonBlank(consumerGroup, "consumerGroup");
            experienceId = Objects.requireNonNull(experienceId, "experienceId");
            poolId = Objects.requireNonNull(poolId, "poolId");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
            timeout = Objects.requireNonNull(timeout, "timeout");
            pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (pollInterval.isZero() || pollInterval.isNegative()) {
                throw new IllegalArgumentException("pollInterval must be positive");
            }
        }

        static Config fromEnvironment(RuntimeEnvironment environment) {
            Objects.requireNonNull(environment, "environment");
            InstanceId instanceId = new InstanceId(environment.value("FULCRUM_INSTANCE_ID")
                    .orElse(LobbySharedShardAllocationProvisioner.DEFAULT_INSTANCE_ID));
            return new Config(
                    required(environment, "FULCRUM_KAFKA_BOOTSTRAP_SERVERS"),
                    environment.value("FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC").orElse(DEFAULT_STATE_TOPIC),
                    environment.value("FULCRUM_LOBBY_ALLOCATION_STATE_CONSUMER_GROUP")
                            .orElse(DEFAULT_CONSUMER_GROUP + "-" + instanceId.value()),
                    new ExperienceId(environment.value("FULCRUM_LOBBY_EXPERIENCE_ID")
                            .orElse(LobbySharedShardAllocationProvisioner.DEFAULT_EXPERIENCE_ID)),
                    new PoolId(environment.value("FULCRUM_LOBBY_POOL_ID")
                            .orElse(LobbySharedShardAllocationProvisioner.DEFAULT_POOL_ID)),
                    new SessionId(environment.value("FULCRUM_LOBBY_SESSION_ID")
                            .orElse(LobbySharedShardAllocationProvisioner.DEFAULT_SESSION_ID)),
                    new ResolvedManifestId(environment.value("FULCRUM_LOBBY_RESOLVED_MANIFEST_ID")
                            .orElse(LobbySharedShardAllocationProvisioner.DEFAULT_RESOLVED_MANIFEST_ID)),
                    environment.value("FULCRUM_LOBBY_ALLOCATION_MATERIALIZATION_TIMEOUT")
                            .map(Duration::parse)
                            .orElse(DEFAULT_TIMEOUT),
                    environment.value("FULCRUM_LOBBY_ALLOCATION_MATERIALIZATION_POLL_INTERVAL")
                            .map(Duration::parse)
                            .orElse(DEFAULT_POLL_INTERVAL));
        }
    }

    record MatchedState(
            ExternalControllerWorkerCatalog.StoredSharedShardAllocation allocation,
            int recordsScanned) {
        MatchedState {
            allocation = Objects.requireNonNull(allocation, "allocation");
            if (recordsScanned < 1) {
                throw new IllegalArgumentException("recordsScanned must be positive");
            }
        }
    }

    record Result(
            ExternalControllerWorkerCatalog.StoredSharedShardAllocation allocation,
            int recordsScanned) {
        Result {
            allocation = Objects.requireNonNull(allocation, "allocation");
            if (recordsScanned < 0) {
                throw new IllegalArgumentException("recordsScanned must not be negative");
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
