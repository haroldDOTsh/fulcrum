package sh.harold.fulcrum.testkit.substrate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FulcrumSubstrateStackTest {
    @Test
    void substrateStackStarts() {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            assertAll(
                    () -> assertTrue(stack.kafkaRunning(), "Kafka command log must be running"),
                    () -> assertTrue(stack.postgresRunning(), "PostgreSQL system store must be running"),
                    () -> assertTrue(stack.cassandraRunning(), "Cassandra projection store must be running"),
                    () -> assertTrue(stack.valkeyRunning(), "Valkey cache must be running"),
                    () -> assertFalse(stack.kafkaBootstrapServers().isBlank(), "Kafka must expose bootstrap servers"),
                    () -> assertFalse(stack.postgresJdbcUrl().isBlank(), "PostgreSQL must expose a JDBC URL"),
                    () -> assertFalse(stack.cassandraContactPoint().isBlank(), "Cassandra must expose a contact point"),
                    () -> assertFalse(stack.valkeyEndpoint().isBlank(), "Valkey must expose an endpoint"),
                    () -> assertTrue(stack.postgresAcceptsConnections(), "PostgreSQL must accept readiness probes"),
                    () -> assertTrue(stack.cassandraReportsReady(), "Cassandra must report readiness"),
                    () -> assertTrue(stack.valkeyRespondsToPing(), "Valkey must answer PING"));
        }
    }
}
