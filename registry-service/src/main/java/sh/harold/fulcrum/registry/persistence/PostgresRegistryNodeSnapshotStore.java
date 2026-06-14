package sh.harold.fulcrum.registry.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionBudget;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL sink for registry metadata snapshots.
 */
public final class PostgresRegistryNodeSnapshotStore implements RegistryNodeSnapshotStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresRegistryNodeSnapshotStore.class);
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {};
    private static final String REGISTRY_SERVICE_ID = "registry-service";
    private static final String SNAPSHOT_TABLE = "registry_node_snapshots";
    private static final String MIGRATION_TABLE = "fulcrum_schema_migrations";
    private static final String SCHEMA_MIGRATION_VERSION = "001";
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;
    private static final String SNAPSHOT_ATTESTATION_INDEX = "idx_registry_node_snapshots_attestation";
    private static final List<String> REQUIRED_SNAPSHOT_COLUMNS = List.of(
        "node_id",
        "node_type",
        "address",
        "port",
        "role",
        "state",
        "capacity",
        "metadata",
        "registered_at",
        "updated_at",
        "snapshot_id",
        "snapshot_source",
        "snapshot_version",
        "snapshot_fingerprint"
    );

    private final HikariDataSource dataSource;
    private final String snapshotSource;
    private final PostgresConnectionBudget.Declaration poolDeclaration;
    private final SnapshotSchemaEvidence schemaEvidence;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresRegistryNodeSnapshotStore(String jdbcUrl,
                                             String username,
                                             String password,
                                             String poolName,
                                             Properties properties) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        String effectivePoolName = poolName == null || poolName.isBlank() ? "FulcrumRegistryPostgresPool" : poolName;
        config.setPoolName(effectivePoolName);
        config.setMaximumPoolSize(intProperty(properties, "maximum-pool-size", 2));
        config.setMinimumIdle(intProperty(properties, "minimum-idle", 0));
        config.setConnectionTimeout(longProperty(properties, "connection-timeout", 5000L));
        config.setIdleTimeout(longProperty(properties, "idle-timeout", 300000L));
        config.setMaxLifetime(longProperty(properties, "max-lifetime", 1800000L));
        this.snapshotSource = effectivePoolName;
        this.poolDeclaration = PostgresConnectionBudget.fromHikariConfig(
            "registry-service:node-snapshots",
            "registry-service",
            PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
            config
        );
        FulcrumSchemaContract schemaContract = FulcrumSchemaContract.loadDefault();
        FulcrumSchemaContract.TableContract snapshotTableContract = validateSchemaContract(schemaContract);
        this.dataSource = new HikariDataSource(config);
        this.schemaEvidence = validateSchema(schemaContract, snapshotTableContract);
    }

    @Override
    public List<RegistryNodeSnapshot> loadSnapshots() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT node_id, node_type, address, port, role, state, capacity,
                        metadata::text AS metadata, registered_at, updated_at,
                        snapshot_version, snapshot_id, snapshot_source, snapshot_fingerprint
                 FROM registry_node_snapshots
                 ORDER BY updated_at DESC
                 """);
             ResultSet resultSet = statement.executeQuery()) {
            List<RegistryNodeSnapshot> snapshots = new ArrayList<>();
            while (resultSet.next()) {
                RegistryNodeSnapshot snapshot = readSnapshot(resultSet);
                if (!snapshot.permitsRestore()) {
                    LOGGER.warn(
                        "Skipping registry snapshot {}:{} from {} because attestation fingerprint does not match payload",
                        snapshot.nodeType(),
                        snapshot.nodeId(),
                        snapshot.snapshotSource()
                    );
                    continue;
                }
                snapshots.add(snapshot);
            }
            return snapshots;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load registry node snapshots", exception);
        }
    }

    @Override
    public void snapshotServer(RegisteredServerData server) {
        if (server == null) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tempId", server.getTempId());
        metadata.put("serverType", server.getServerType());
        metadata.put("playerCount", server.getPlayerCount());
        metadata.put("tps", server.getTps());
        metadata.put("memoryUsage", server.getMemoryUsage());
        metadata.put("cpuUsage", server.getCpuUsage());
        if (server.getDataAuthorityAttestation() != null) {
            metadata.put(
                "dataAuthorityAttestation",
                objectMapper.convertValue(server.getDataAuthorityAttestation(), STRING_OBJECT_MAP)
            );
        }
        if (server.getAuthorityDeliveryManifest() != null) {
            metadata.put(
                "authorityDeliveryManifest",
                objectMapper.convertValue(server.getAuthorityDeliveryManifest(), STRING_OBJECT_MAP)
            );
        }
        metadata.put("slotFamilies", server.getSlotFamilyCapacities());
        metadata.put("slots", server.getSlots().stream()
            .map(this::slotMetadata)
            .toList());

        upsert(
            server.getServerId(),
            "BACKEND",
            server.getAddress(),
            server.getPort(),
            server.getRole(),
            server.getStatus().name(),
            server.getMaxCapacity(),
            metadata
        );
    }

    @Override
    public void snapshotProxy(RegisteredProxyData proxy) {
        if (proxy == null) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("registrationState", proxy.getRegistrationState().name());
        metadata.put("lastHeartbeat", proxy.getLastHeartbeat());

        upsert(
            proxy.getProxyIdString(),
            "PROXY",
            proxy.getAddress(),
            proxy.getPort(),
            "proxy",
            proxy.getStatus().name(),
            0,
            metadata
        );
    }

    @Override
    public void markOffline(String nodeId, String nodeType, String state) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        String effectiveNodeType = nodeType == null || nodeType.isBlank() ? "UNKNOWN" : nodeType;
        String effectiveState = state == null || state.isBlank() ? "OFFLINE" : state;
        try (Connection connection = dataSource.getConnection()) {
            RegistryNodeSnapshot current = findSnapshot(connection, nodeId, effectiveNodeType);
            if (current == null) {
                return;
            }
            RegistryNodeSnapshot attested = new RegistryNodeSnapshot(
                current.nodeId(),
                current.nodeType(),
                current.address(),
                current.port(),
                current.role(),
                effectiveState,
                current.capacity(),
                current.metadata(),
                current.registeredAt(),
                Instant.now()
            ).withAttestation(UUID.randomUUID(), snapshotSource);
            try (PreparedStatement statement = connection.prepareStatement("""
                 UPDATE registry_node_snapshots
                 SET state = ?,
                     snapshot_id = ?,
                     snapshot_source = ?,
                     snapshot_version = ?,
                     snapshot_fingerprint = ?,
                     updated_at = CURRENT_TIMESTAMP
                 WHERE node_id = ? AND node_type = ?
                 """)) {
                statement.setString(1, attested.state());
                statement.setObject(2, attested.snapshotId());
                statement.setString(3, attested.snapshotSource());
                statement.setInt(4, attested.snapshotVersion());
                statement.setString(5, attested.snapshotFingerprint());
                statement.setString(6, nodeId);
                statement.setString(7, effectiveNodeType);
                statement.executeUpdate();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to mark registry node offline: " + nodeId, exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    public PostgresConnectionBudget.Declaration poolDeclaration() {
        return poolDeclaration;
    }

    @Override
    public SnapshotSchemaEvidence schemaEvidence() {
        return schemaEvidence;
    }

    private Map<String, Object> slotMetadata(LogicalSlotRecord slot) {
        Map<String, Object> data = new HashMap<>();
        data.put("slotId", slot.getSlotId());
        data.put("slotSuffix", slot.getSlotSuffix());
        data.put("gameType", slot.getGameType());
        data.put("status", slot.getStatus().name());
        data.put("maxPlayers", slot.getMaxPlayers());
        data.put("onlinePlayers", slot.getOnlinePlayers());
        data.put("metadata", slot.getMetadata());
        return data;
    }

    private void upsert(String nodeId,
                        String nodeType,
                        String address,
                        int port,
                        String role,
                        String state,
                        int capacity,
                        Map<String, Object> metadata) {
        RegistryNodeSnapshot snapshot = new RegistryNodeSnapshot(
            nodeId,
            nodeType,
            address,
            port,
            role == null || role.isBlank() ? "default" : role,
            state,
            capacity,
            metadata != null ? metadata : Map.of(),
            Instant.now(),
            Instant.now()
        ).withAttestation(UUID.randomUUID(), snapshotSource);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO registry_node_snapshots (
                     node_id, node_type, address, port, role, state, capacity, metadata,
                     snapshot_id, snapshot_source, snapshot_version, snapshot_fingerprint, updated_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                 ON CONFLICT (node_id) DO UPDATE SET
                     node_type = EXCLUDED.node_type,
                     address = EXCLUDED.address,
                     port = EXCLUDED.port,
                     role = EXCLUDED.role,
                     state = EXCLUDED.state,
                     capacity = EXCLUDED.capacity,
                     metadata = EXCLUDED.metadata,
                     snapshot_id = EXCLUDED.snapshot_id,
                     snapshot_source = EXCLUDED.snapshot_source,
                     snapshot_version = EXCLUDED.snapshot_version,
                     snapshot_fingerprint = EXCLUDED.snapshot_fingerprint,
                     updated_at = CURRENT_TIMESTAMP
                 """)) {
            statement.setString(1, snapshot.nodeId());
            statement.setString(2, snapshot.nodeType());
            statement.setString(3, snapshot.address());
            statement.setInt(4, snapshot.port());
            statement.setString(5, snapshot.role());
            statement.setString(6, snapshot.state());
            statement.setInt(7, snapshot.capacity());
            statement.setString(8, objectMapper.writeValueAsString(snapshot.metadata()));
            statement.setObject(9, snapshot.snapshotId());
            statement.setString(10, snapshot.snapshotSource());
            statement.setInt(11, snapshot.snapshotVersion());
            statement.setString(12, snapshot.snapshotFingerprint());
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to snapshot registry node: " + nodeId, exception);
        }
    }

    private SnapshotSchemaEvidence validateSchema(FulcrumSchemaContract schemaContract,
                                                  FulcrumSchemaContract.TableContract tableContract) {
        try (Connection connection = dataSource.getConnection()) {
            requireRelation(connection, SNAPSHOT_TABLE);
            SnapshotSchemaObservation observation = observeSnapshotSchema(connection);
            validateSnapshotSchemaObservation(observation);
            return SnapshotSchemaEvidence.enabled(
                schemaContract.version(),
                schemaContract.fingerprint(),
                tableContract.tableName(),
                tableContract.ddlOwner(),
                tableContract.dataOwner(),
                tableContract.createdBy(),
                SCHEMA_MIGRATION_VERSION,
                SCHEMA_MIGRATION,
                observation.schemaMigrationReceipt()
            );
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Failed to validate registry_node_snapshots table. Run data-api migrations before startup.",
                exception
            );
        }
    }

    private static void requireRelation(Connection connection, String relationName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            statement.setString(1, relationName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getString(1) == null) {
                    throw new IllegalStateException(
                        "Missing required " + relationName + " table. Run data-api migrations before startup."
                    );
                }
            }
        }
    }

    private static SnapshotSchemaObservation observeSnapshotSchema(Connection connection) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT attname
            FROM pg_attribute
            WHERE attrelid = to_regclass(?)
              AND attnum > 0
              AND NOT attisdropped
            ORDER BY attnum
            """)) {
            statement.setString(1, SNAPSHOT_TABLE);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("attname"));
                }
            }
        }

        Set<String> indexes = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT index_class.relname AS index_name
            FROM pg_index indexed
            JOIN pg_class index_class ON index_class.oid = indexed.indexrelid
            WHERE indexed.indrelid = to_regclass(?)
            """)) {
            statement.setString(1, SNAPSHOT_TABLE);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    indexes.add(resultSet.getString("index_name"));
                }
            }
        }

        String migrationReceipt = null;
        if (relationExists(connection, MIGRATION_TABLE)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource_path
                FROM fulcrum_schema_migrations
                WHERE version = ?
                """)) {
                statement.setString(1, SCHEMA_MIGRATION_VERSION);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        migrationReceipt = resultSet.getString("resource_path");
                    }
                }
            }
        }

        return new SnapshotSchemaObservation(columns, indexes, migrationReceipt);
    }

    private static boolean relationExists(Connection connection, String relationName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            statement.setString(1, relationName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getString(1) != null;
            }
        }
    }

    static void validateSnapshotSchemaObservation(SnapshotSchemaObservation observation) {
        Objects.requireNonNull(observation, "observation");
        List<String> missingColumns = REQUIRED_SNAPSHOT_COLUMNS.stream()
            .filter(column -> !observation.columns().contains(column))
            .toList();
        if (!missingColumns.isEmpty()) {
            throw new IllegalStateException(
                SNAPSHOT_TABLE + " schema is missing required columns " + missingColumns
                    + ". Run data-api migration " + SCHEMA_MIGRATION + " before startup."
            );
        }
        if (!observation.indexes().contains(SNAPSHOT_ATTESTATION_INDEX)) {
            throw new IllegalStateException(
                SNAPSHOT_TABLE + " schema is missing required index " + SNAPSHOT_ATTESTATION_INDEX
                    + ". Run data-api migration " + SCHEMA_MIGRATION + " before startup."
            );
        }
        if (!SCHEMA_MIGRATION.equals(observation.schemaMigrationReceipt())) {
            throw new IllegalStateException(
                SNAPSHOT_TABLE + " schema is missing migration receipt "
                    + SCHEMA_MIGRATION_VERSION + " for " + SCHEMA_MIGRATION
                    + ". Run data-api migrations before startup."
            );
        }
    }

    static FulcrumSchemaContract.TableContract validateSchemaContract(FulcrumSchemaContract contract) {
        Objects.requireNonNull(contract, "contract");
        return contract.requireDataApiOwnedTable(SNAPSHOT_TABLE, REGISTRY_SERVICE_ID);
    }

    record SnapshotSchemaObservation(
        Set<String> columns,
        Set<String> indexes,
        String schemaMigrationReceipt
    ) {
        SnapshotSchemaObservation {
            columns = columns == null ? Set.of() : Set.copyOf(columns);
            indexes = indexes == null ? Set.of() : Set.copyOf(indexes);
        }
    }

    private RegistryNodeSnapshot findSnapshot(Connection connection, String nodeId, String nodeType) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT node_id, node_type, address, port, role, state, capacity,
                   metadata::text AS metadata, registered_at, updated_at,
                   snapshot_version, snapshot_id, snapshot_source, snapshot_fingerprint
            FROM registry_node_snapshots
            WHERE node_id = ? AND node_type = ?
            """)) {
            statement.setString(1, nodeId);
            statement.setString(2, nodeType);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readSnapshot(resultSet) : null;
            }
        }
    }

    private RegistryNodeSnapshot readSnapshot(ResultSet resultSet) throws Exception {
        return new RegistryNodeSnapshot(
            resultSet.getString("node_id"),
            resultSet.getString("node_type"),
            resultSet.getString("address"),
            resultSet.getInt("port"),
            resultSet.getString("role"),
            resultSet.getString("state"),
            resultSet.getInt("capacity"),
            readMetadata(resultSet.getString("metadata")),
            instant(resultSet, "registered_at"),
            instant(resultSet, "updated_at"),
            resultSet.getInt("snapshot_version"),
            resultSet.getObject("snapshot_id", UUID.class),
            resultSet.getString("snapshot_source"),
            resultSet.getString("snapshot_fingerprint")
        );
    }

    private Map<String, Object> readMetadata(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> metadata = objectMapper.readValue(json, STRING_OBJECT_MAP);
        return metadata == null ? Map.of() : metadata;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = properties != null ? properties.getProperty(key) : null;
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static long longProperty(Properties properties, String key, long fallback) {
        String value = properties != null ? properties.getProperty(key) : null;
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }
}
