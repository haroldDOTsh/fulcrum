package sh.harold.fulcrum.registry.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresMigrationRunner;
import sh.harold.fulcrum.registry.server.RegisteredServerData;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-postgres")
class PostgresRegistryNodeSnapshotStoreIntegrationTest {
    private static final String EXTERNAL_JDBC_URL_PROPERTY = "fulcrum.test.postgres.jdbcUrl";
    private static final String EXTERNAL_USERNAME_PROPERTY = "fulcrum.test.postgres.username";
    private static final String EXTERNAL_PASSWORD_PROPERTY = "fulcrum.test.postgres.password";
    private static final String EXTERNAL_ALLOW_MUTATION_PROPERTY = "fulcrum.test.postgres.allowMutation";
    private static final String EXTERNAL_ALLOW_ROLE_DDL_PROPERTY = "fulcrum.test.postgres.allowRoleDdl";
    private static final String EXTERNAL_REQUIRE_LIVE_PROPERTY = "fulcrum.test.postgres.requireLive";
    private static final String EXTERNAL_JDBC_URL_ENV = "FULCRUM_TEST_POSTGRES_JDBC_URL";
    private static final String EXTERNAL_USERNAME_ENV = "FULCRUM_TEST_POSTGRES_USERNAME";
    private static final String EXTERNAL_PASSWORD_ENV = "FULCRUM_TEST_POSTGRES_PASSWORD";
    private static final String EXTERNAL_ALLOW_MUTATION_ENV = "FULCRUM_TEST_POSTGRES_ALLOW_MUTATION";
    private static final String EXTERNAL_ALLOW_ROLE_DDL_ENV = "FULCRUM_TEST_POSTGRES_ALLOW_ROLE_DDL";
    private static final String EXTERNAL_REQUIRE_LIVE_ENV = "FULCRUM_TEST_POSTGRES_REQUIRE_LIVE";
    private static final String RUNTIME_ROLE = "registry_snapshot_runtime";
    private static final String RUNTIME_PASSWORD = "registry_snapshot_runtime_password";
    private static final String PRIVILEGE_DENIED = "42501";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static RuntimeException containerStartFailure;

    private LivePostgresTarget target;
    private PostgresConnectionAdapter ownerAdapter;

    @BeforeEach
    void setUp() {
        target = requireLivePostgresTarget();
        ownerAdapter = new PostgresConnectionAdapter(
            target.jdbcUrl(),
            target.username(),
            target.password(),
            "registry-snapshot-owner",
            poolProperties()
        );
        new PostgresMigrationRunner(ownerAdapter).runClasspathMigrations(FulcrumDataMigrations.all());
        resetSnapshotRows();
        configureRuntimeRole();
    }

    @AfterEach
    void tearDown() {
        if (ownerAdapter != null) {
            ownerAdapter.close();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    private static LivePostgresTarget requireLivePostgresTarget() {
        LivePostgresTarget external = externalPostgresTarget();
        if (external != null) {
            return external;
        }
        if (containerStartFailure != null) {
            return unavailableLivePostgres(containerStartFailure);
        }
        try {
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
            }
            return new LivePostgresTarget(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
            );
        } catch (RuntimeException exception) {
            containerStartFailure = exception;
            return unavailableLivePostgres(exception);
        }
    }

    private static LivePostgresTarget externalPostgresTarget() {
        String jdbcUrl = externalSetting(EXTERNAL_JDBC_URL_PROPERTY, EXTERNAL_JDBC_URL_ENV);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String username = externalSetting(EXTERNAL_USERNAME_PROPERTY, EXTERNAL_USERNAME_ENV);
        requireOrAssumeLivePostgres(
            username != null && !username.isBlank(),
            "External PostgreSQL registry snapshot proof requires "
                + EXTERNAL_USERNAME_ENV
                + " or -D"
                + EXTERNAL_USERNAME_PROPERTY
        );
        requireOrAssumeLivePostgres(
            externalFlag(EXTERNAL_ALLOW_MUTATION_PROPERTY, EXTERNAL_ALLOW_MUTATION_ENV),
            "External PostgreSQL registry snapshot proof requires "
                + EXTERNAL_ALLOW_MUTATION_ENV
                + "=true or -D"
                + EXTERNAL_ALLOW_MUTATION_PROPERTY
                + "=true because the suite runs migrations and deletes registry snapshot rows"
        );
        requireOrAssumeLivePostgres(
            externalFlag(EXTERNAL_ALLOW_ROLE_DDL_PROPERTY, EXTERNAL_ALLOW_ROLE_DDL_ENV),
            "External PostgreSQL registry snapshot proof requires "
                + EXTERNAL_ALLOW_ROLE_DDL_ENV
                + "=true or -D"
                + EXTERNAL_ALLOW_ROLE_DDL_PROPERTY
                + "=true because the suite creates roles and changes schema/database privileges"
        );
        String password = externalSetting(EXTERNAL_PASSWORD_PROPERTY, EXTERNAL_PASSWORD_ENV);
        return new LivePostgresTarget(jdbcUrl, username, password == null ? "" : password);
    }

    private static boolean externalFlag(String propertyName, String environmentName) {
        return Boolean.parseBoolean(externalSetting(propertyName, environmentName));
    }

    private static LivePostgresTarget unavailableLivePostgres(RuntimeException exception) {
        String message = livePostgresSkipMessage(exception);
        if (livePostgresRequired()) {
            throw new AssertionError(message, exception);
        }
        Assumptions.assumeTrue(false, message);
        throw exception;
    }

    private static void requireOrAssumeLivePostgres(boolean condition, String message) {
        if (condition) {
            return;
        }
        if (livePostgresRequired()) {
            throw new AssertionError(message);
        }
        Assumptions.assumeTrue(false, message);
    }

    private static boolean livePostgresRequired() {
        return Boolean.parseBoolean(externalSetting(
            EXTERNAL_REQUIRE_LIVE_PROPERTY,
            EXTERNAL_REQUIRE_LIVE_ENV
        ));
    }

    private static String externalSetting(String propertyName, String environmentName) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String livePostgresSkipMessage(RuntimeException exception) {
        return "Registry snapshot live PostgreSQL proof requires Docker/Testcontainers or "
            + EXTERNAL_JDBC_URL_ENV
            + " with "
            + EXTERNAL_ALLOW_MUTATION_ENV
            + "=true and "
            + EXTERNAL_ALLOW_ROLE_DDL_ENV
            + "=true; Testcontainers startup failed: "
            + rootMessage(exception);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Test
    void snapshotStoreRunsWithDmlOnlyRuntimeRole() throws Exception {
        assertRuntimePrivilegeMatrix();

        try (PostgresRegistryNodeSnapshotStore store = runtimeStore()) {
            RegisteredServerData server = new RegisteredServerData(
                "mini-dml",
                "temp-mini-dml",
                "mini",
                "127.0.0.1",
                25565,
                40
            );
            server.setRole("game");
            server.setStatus(RegisteredServerData.Status.AVAILABLE);

            store.snapshotServer(server);
            store.markOffline("mini-dml", "BACKEND", RegisteredServerData.Status.DEAD.name());

            assertThat(store.schemaEvidence().enabled()).isTrue();
            assertThat(store.schemaEvidence().ddlOwner()).isEqualTo("data-api");
            assertThat(store.schemaEvidence().schemaMigrationReceipt())
                .isEqualTo(FulcrumDataMigrations.SCHEMA_MIGRATION);
        }

        try (PostgresRegistryNodeSnapshotStore reconnected = runtimeStore()) {
            List<RegistryNodeSnapshot> snapshots = reconnected.loadSnapshots();

            assertThat(snapshots).hasSize(1);
            RegistryNodeSnapshot snapshot = snapshots.get(0);
            assertThat(snapshot.nodeId()).isEqualTo("mini-dml");
            assertThat(snapshot.nodeType()).isEqualTo("BACKEND");
            assertThat(snapshot.state()).isEqualTo(RegisteredServerData.Status.DEAD.name());
            assertThat(snapshot.hasValidAttestation()).isTrue();
            assertThat(snapshot.snapshotSource()).isEqualTo("registry-snapshot-runtime");
        }

        assertDenied("create table", "CREATE TABLE public.registry_snapshot_runtime_ddl_probe (id INTEGER)");
        assertDenied("create temp table", "CREATE TEMP TABLE registry_snapshot_runtime_temp_probe (id INTEGER)");
        assertDenied("alter snapshot table", "ALTER TABLE registry_node_snapshots ADD COLUMN ddl_probe TEXT");
        assertDenied("create snapshot index", "CREATE INDEX ddl_probe_idx ON registry_node_snapshots (node_id)");
        assertDenied("truncate snapshot table", "TRUNCATE TABLE registry_node_snapshots");
        assertDenied("drop snapshot table", "DROP TABLE registry_node_snapshots");
        assertDenied(
            "insert migration receipt",
            """
            INSERT INTO fulcrum_schema_migrations (version, resource_path, checksum)
            VALUES ('ddl-probe', 'migrations/ddl_probe.sql', 'checksum')
            """
        );
        assertDenied(
            "update migration receipt",
            """
            UPDATE fulcrum_schema_migrations
            SET checksum = checksum
            WHERE version = '001'
            """
        );
    }

    private PostgresRegistryNodeSnapshotStore runtimeStore() {
        return new PostgresRegistryNodeSnapshotStore(
            target.jdbcUrl(),
            RUNTIME_ROLE,
            RUNTIME_PASSWORD,
            "registry-snapshot-runtime",
            poolProperties()
        );
    }

    private void assertRuntimePrivilegeMatrix() {
        try (Connection connection = runtimeConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("""
                 SELECT current_user,
                        has_schema_privilege('public', 'USAGE') AS schema_usage,
                        has_schema_privilege('public', 'CREATE') AS schema_create,
                        has_database_privilege(current_database(), 'TEMP') AS database_temp,
                        has_table_privilege('registry_node_snapshots', 'SELECT') AS snapshots_select,
                        has_table_privilege('registry_node_snapshots', 'INSERT') AS snapshots_insert,
                        has_table_privilege('registry_node_snapshots', 'UPDATE') AS snapshots_update,
                        has_table_privilege('registry_node_snapshots', 'DELETE') AS snapshots_delete,
                        has_table_privilege('registry_node_snapshots', 'TRUNCATE') AS snapshots_truncate,
                        has_table_privilege('fulcrum_schema_migrations', 'SELECT') AS migrations_select,
                        has_table_privilege('fulcrum_schema_migrations', 'INSERT') AS migrations_insert,
                        has_table_privilege('fulcrum_schema_migrations', 'UPDATE') AS migrations_update,
                        has_table_privilege('fulcrum_schema_migrations', 'DELETE') AS migrations_delete,
                        to_regclass('registry_node_snapshots') IS NOT NULL AS catalog_readable
                 """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("current_user")).isEqualTo(RUNTIME_ROLE);
            assertThat(resultSet.getBoolean("schema_usage")).isTrue();
            assertThat(resultSet.getBoolean("schema_create")).isFalse();
            assertThat(resultSet.getBoolean("database_temp")).isFalse();
            assertThat(resultSet.getBoolean("snapshots_select")).isTrue();
            assertThat(resultSet.getBoolean("snapshots_insert")).isTrue();
            assertThat(resultSet.getBoolean("snapshots_update")).isTrue();
            assertThat(resultSet.getBoolean("snapshots_delete")).isFalse();
            assertThat(resultSet.getBoolean("snapshots_truncate")).isFalse();
            assertThat(resultSet.getBoolean("migrations_select")).isTrue();
            assertThat(resultSet.getBoolean("migrations_insert")).isFalse();
            assertThat(resultSet.getBoolean("migrations_update")).isFalse();
            assertThat(resultSet.getBoolean("migrations_delete")).isFalse();
            assertThat(resultSet.getBoolean("catalog_readable")).isTrue();
            assertThat(resultSet.next()).isFalse();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to assert registry snapshot runtime privileges", exception);
        }

        try (Connection connection = ownerAdapter.getConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("""
                 SELECT pg_get_userbyid(relowner) AS owner
                 FROM pg_class
                 WHERE oid = 'registry_node_snapshots'::regclass
                 """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("owner")).isNotEqualTo(RUNTIME_ROLE);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to assert registry snapshot table ownership", exception);
        }
    }

    private void assertDenied(String probeName, String sql) throws SQLException {
        try (Connection connection = runtimeConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            SQLException failure = null;
            try {
                statement.execute(sql);
            } catch (SQLException exception) {
                failure = exception;
            } finally {
                connection.rollback();
            }

            assertThat((Object) failure)
                .as(probeName)
                .isNotNull();
            assertThat(failure.getSQLState())
                .as(probeName + " SQLSTATE")
                .isEqualTo(PRIVILEGE_DENIED);
        }
    }

    private Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(target.jdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
    }

    private void resetSnapshotRows() {
        try (Connection connection = ownerAdapter.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM registry_node_snapshots");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to reset registry snapshot rows", exception);
        }
    }

    private void configureRuntimeRole() {
        try (Connection connection = ownerAdapter.getConnection();
             Statement statement = connection.createStatement()) {
            String databaseName = currentDatabaseName(connection);
            try {
                statement.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            } catch (SQLException exception) {
                if (!"42710".equals(exception.getSQLState())) {
                    throw exception;
                }
            }
            statement.execute("ALTER ROLE " + RUNTIME_ROLE + " WITH LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute(
                "REVOKE TEMPORARY ON DATABASE " + quoteIdentifier(databaseName) + " FROM PUBLIC"
            );
            statement.execute(
                "REVOKE TEMPORARY ON DATABASE " + quoteIdentifier(databaseName) + " FROM "
                    + RUNTIME_ROLE
            );
            statement.execute("REVOKE ALL PRIVILEGES ON SCHEMA public FROM " + RUNTIME_ROLE);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
            statement.execute("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM " + RUNTIME_ROLE);
            statement.execute("GRANT SELECT ON fulcrum_schema_migrations TO " + RUNTIME_ROLE);
            statement.execute(
                "GRANT SELECT, INSERT, UPDATE ON registry_node_snapshots TO " + RUNTIME_ROLE
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to configure registry snapshot runtime role", exception);
        }
    }

    private static String currentDatabaseName(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT current_database()")) {
            if (!resultSet.next()) {
                throw new SQLException("current_database() returned no rows");
            }
            return resultSet.getString(1);
        }
    }

    private static Properties poolProperties() {
        Properties properties = new Properties();
        properties.setProperty("maximum-pool-size", "1");
        properties.setProperty("minimum-idle", "0");
        properties.setProperty("connection-timeout", "5000");
        return properties;
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record LivePostgresTarget(String jdbcUrl, String username, String password) {
        private LivePostgresTarget {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbcUrl is required");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("username is required");
            }
            password = password == null ? "" : password;
        }
    }
}
