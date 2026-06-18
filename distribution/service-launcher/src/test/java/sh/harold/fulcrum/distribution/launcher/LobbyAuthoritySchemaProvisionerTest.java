package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.testkit.substrate.FulcrumSubstrateStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbyAuthoritySchemaProvisionerTest {
    @Test
    void provisionsCheckedInMigrationResources() {
        RecordingSchemaExecutor executor = new RecordingSchemaExecutor();

        LobbyAuthoritySchemaProvisioner.Result result = LobbyAuthoritySchemaProvisioner.provision(config(), executor);

        assertEquals(2, result.postgresStatementCount());
        assertEquals(11, result.cassandraStatementCount());
        assertEquals(2, executor.postgresStatements.size());
        assertEquals(11, executor.cassandraStatements.size());
        assertTrue(executor.postgresStatements.getFirst().contains("CREATE TABLE IF NOT EXISTS authority_records"));
        assertTrue(executor.postgresStatements.getLast().contains("CREATE TABLE IF NOT EXISTS authority_decisions"));
        assertTrue(executor.cassandraStatements.getFirst().contains("CREATE KEYSPACE IF NOT EXISTS fulcrum"));
        assertTrue(executor.cassandraStatements.stream()
                .anyMatch(statement -> statement.contains("CREATE TABLE IF NOT EXISTS fulcrum.standard_player_profile_effective_hot")));
        assertTrue(executor.cassandraStatements.stream()
                .anyMatch(statement -> statement.contains("CREATE TABLE IF NOT EXISTS fulcrum.standard_rank_effective_hot")));
        assertTrue(executor.cassandraStatements.stream()
                .anyMatch(statement -> statement.contains("CREATE TABLE IF NOT EXISTS fulcrum.standard_punishment_active_hot")));
        assertTrue(executor.cassandraStatements.stream()
                .anyMatch(statement -> statement.contains("CREATE TABLE IF NOT EXISTS fulcrum.standard_economy_balance_hot")));
        assertTrue(executor.cassandraStatements.stream()
                .anyMatch(statement -> statement.contains("CREATE TABLE IF NOT EXISTS fulcrum.standard_stats_counter_hot")));
    }

    @Test
    void appliesMigrationResourcesAgainstSubstrateStores() {
        try (FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start();
             LobbyAuthoritySchemaProvisioner.StoreSchemaExecutor executor =
                     LobbyAuthoritySchemaProvisioner.StoreSchemaExecutor.connect(config(stack))) {
            LobbyAuthoritySchemaProvisioner.Result result =
                    LobbyAuthoritySchemaProvisioner.provision(config(stack), executor);

            assertEquals(2, result.postgresStatementCount());
            assertEquals(11, result.cassandraStatementCount());
            assertEquals("0", stack.queryPostgresScalar("SELECT count(*) FROM authority_records;"));
            assertTrue(stack.queryCassandra("SELECT * FROM fulcrum.standard_player_profile_effective_hot;")
                    .contains("subject_id"));
            assertTrue(stack.queryCassandra("SELECT * FROM fulcrum.standard_economy_balance_hot;")
                    .contains("balance_minor_units"));
            assertTrue(stack.queryCassandra("SELECT * FROM fulcrum.standard_stats_counter_hot;")
                    .contains("stat_key"));
        }
    }

    @Test
    void configReadsStoreBindingsFromEnvironment() {
        LobbyAuthoritySchemaProvisioner.Config config = LobbyAuthoritySchemaProvisioner.Config.fromEnvironment(
                RuntimeEnvironment.of(Map.of(
                        "FULCRUM_POSTGRES_JDBC_URL", "jdbc:postgresql://postgres:5432/fulcrum",
                        "FULCRUM_POSTGRES_USERNAME", "fulcrum",
                        "FULCRUM_POSTGRES_PASSWORD", "secret",
                        "FULCRUM_CASSANDRA_CONTACT_POINTS", "cassandra-a:9042,cassandra-b:9142",
                        "FULCRUM_CASSANDRA_LOCAL_DATACENTER", "dc-lobby")));

        assertEquals("jdbc:postgresql://postgres:5432/fulcrum", config.postgres().jdbcUrl());
        assertEquals("fulcrum", config.postgres().username());
        assertEquals("secret", config.postgres().password());
        assertEquals(2, config.cassandraContactPoints().size());
        assertEquals("cassandra-a", config.cassandraContactPoints().getFirst().host());
        assertEquals(9042, config.cassandraContactPoints().getFirst().port());
        assertEquals("cassandra-b", config.cassandraContactPoints().get(1).host());
        assertEquals(9142, config.cassandraContactPoints().get(1).port());
        assertEquals("dc-lobby", config.cassandraLocalDatacenter());
    }

    @Test
    void configDefaultsCassandraLocalDatacenter() {
        LobbyAuthoritySchemaProvisioner.Config config = LobbyAuthoritySchemaProvisioner.Config.fromEnvironment(
                RuntimeEnvironment.of(Map.of(
                        "FULCRUM_POSTGRES_JDBC_URL", "jdbc:postgresql://postgres:5432/fulcrum",
                        "FULCRUM_POSTGRES_USERNAME", "fulcrum",
                        "FULCRUM_POSTGRES_PASSWORD", "secret",
                        "FULCRUM_CASSANDRA_CONTACT_POINTS", "cassandra:9042")));

        assertEquals("datacenter1", config.cassandraLocalDatacenter());
    }

    @Test
    void mainRejectsArgumentsBecauseConfigurationComesFromEnvironment() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LobbyAuthoritySchemaProvisioner.main(new String[]{"--postgres=local"}));

        assertTrue(exception.getMessage().contains("environment"));
    }

    @Test
    void statementSplitterRemovesLineCommentsAndBlankSegments() {
        List<String> statements = LobbyAuthoritySchemaProvisioner.statements("""
                -- ignored

                CREATE TABLE first (id text);
                -- ignored too
                CREATE TABLE second (id text);
                """);

        assertEquals(List.of("CREATE TABLE first (id text)", "CREATE TABLE second (id text)"), statements);
    }

    private static LobbyAuthoritySchemaProvisioner.Config config() {
        return new LobbyAuthoritySchemaProvisioner.Config(
                new RuntimeConnectionSettings.PostgresJdbcSettings(
                        "jdbc:postgresql://localhost:5432/fulcrum",
                        "fulcrum",
                        "secret"),
                List.of(new RuntimeConnectionSettings.HostPort("localhost", 9042)),
                "datacenter1");
    }

    private static LobbyAuthoritySchemaProvisioner.Config config(FulcrumSubstrateStack stack) {
        return new LobbyAuthoritySchemaProvisioner.Config(
                new RuntimeConnectionSettings.PostgresJdbcSettings(
                        stack.postgresJdbcUrl(),
                        stack.postgresUsername(),
                        stack.postgresPassword()),
                List.of(new RuntimeConnectionSettings.HostPort(stack.cassandraHost(), stack.cassandraPort())),
                "datacenter1");
    }

    private static final class RecordingSchemaExecutor implements LobbyAuthoritySchemaProvisioner.SchemaExecutor {
        private final List<String> postgresStatements = new ArrayList<>();
        private final List<String> cassandraStatements = new ArrayList<>();

        @Override
        public void executePostgres(String statement) {
            postgresStatements.add(statement);
        }

        @Override
        public void executeCassandra(String statement) {
            cassandraStatements.add(statement);
        }
    }
}
