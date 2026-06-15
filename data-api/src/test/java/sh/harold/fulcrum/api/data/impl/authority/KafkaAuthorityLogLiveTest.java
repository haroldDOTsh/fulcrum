package sh.harold.fulcrum.api.data.impl.authority;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-substrate")
class KafkaAuthorityLogLiveTest {
    private static final DockerImageName KAFKA_IMAGE =
        DockerImageName.parse("confluentinc/cp-kafka:7.6.1");

    @Test
    void liveKafkaCreatesTargetTopologyAndReplaysAppendedCommandRecord() {
        try (KafkaContainer kafka = startKafka()) {
            Properties properties = new Properties();
            properties.setProperty("bootstrap.servers", kafka.getBootstrapServers());
            properties.setProperty("client.id", "fulcrum-authority-live-test");
            properties.setProperty("default.api.timeout.ms", "90000");
            properties.setProperty("request.timeout.ms", "90000");

            try (KafkaAuthorityLog log = liveLog(properties)) {
                createMissingTopicsEventually(log);
                validateTopologyEventually(log);

                UUID playerId = UUID.randomUUID();
                AuthorityCommandRoute route = AuthorityCommandRoute.from(
                    DataAuthority.CommandType.GRANT_RANK,
                    "rank:player:" + playerId
                );

                AuthorityLogRecord appended = log.append(
                    route,
                    AuthorityLogTopicKind.COMMAND,
                    Map.of(
                        "declarationId", "GRANT_RANK",
                        "playerId", playerId.toString()
                    )
                );
                List<AuthorityLogRecord> replayed = log.records(
                    route.commandTopic(),
                    appended.partition(),
                    -1L,
                    1
                );

                assertThat(appended.topic()).isEqualTo(route.commandTopic());
                assertThat(appended.key()).isEqualTo(route.partitionKey());
                assertThat(appended.headers())
                    .containsEntry("authority-log-kind", "COMMAND")
                    .containsEntry("authority-domain", "rank")
                    .containsEntry("authority-route-manifest-fingerprint",
                        DataAuthorityCommandContracts.routeManifestFingerprint());
                assertThat(replayed).singleElement().satisfies(record -> {
                    assertThat(record.topic()).isEqualTo(appended.topic());
                    assertThat(record.key()).isEqualTo(appended.key());
                    assertThat(record.partition()).isEqualTo(appended.partition());
                    assertThat(record.offset()).isEqualTo(appended.offset());
                    assertThat(record.payload()).containsEntry("declarationId", "GRANT_RANK");
                });
            }
        }
    }

    private static void createMissingTopicsEventually(KafkaAuthorityLog log) {
        runEventually("create authority log topics", log::createMissingTopics);
    }

    private static void validateTopologyEventually(KafkaAuthorityLog log) {
        runEventually("validate authority log topology", log::validateTopology);
    }

    private static void runEventually(String action, Runnable operation) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                operation.run();
                return;
            } catch (RuntimeException exception) {
                failure = exception;
                sleep(action);
            }
        }
        throw failure;
    }

    private static void sleep(String action) {
        try {
            Thread.sleep(5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while trying to " + action, exception);
        }
    }

    private static KafkaAuthorityLog liveLog(Properties properties) {
        Properties producerProperties = new Properties();
        producerProperties.putAll(properties);
        producerProperties.put("key.serializer", StringSerializer.class.getName());
        producerProperties.put("value.serializer", StringSerializer.class.getName());
        producerProperties.put("acks", "all");
        producerProperties.put("enable.idempotence", "true");

        Properties consumerProperties = new Properties();
        consumerProperties.putAll(properties);
        consumerProperties.put("key.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("value.deserializer", StringDeserializer.class.getName());

        return new KafkaAuthorityLog(
            Admin.create(properties),
            new KafkaProducer<>(producerProperties),
            AuthorityLogTopology.policiesByTopic(),
            Duration.ofSeconds(60),
            consumerProperties
        );
    }

    private static KafkaContainer startKafka() {
        KafkaContainer container = new KafkaContainer(KAFKA_IMAGE);
        try {
            container.start();
            return container;
        } catch (RuntimeException exception) {
            unavailableLiveSubstrate("Kafka", exception);
            throw exception;
        }
    }

    private static void unavailableLiveSubstrate(String substrate, RuntimeException exception) {
        String message = "Live " + substrate + " proof requires Docker/Testcontainers; startup failed: "
            + exception.getMessage();
        if (liveSubstratesRequired()) {
            throw new IllegalStateException(message, exception);
        }
        Assumptions.assumeTrue(false, message);
    }

    private static boolean liveSubstratesRequired() {
        return Boolean.getBoolean("fulcrum.test.substrates.requireLive")
            || Boolean.parseBoolean(System.getenv().getOrDefault(
                "FULCRUM_TEST_SUBSTRATES_REQUIRE_LIVE",
                "false"
            ));
    }
}
