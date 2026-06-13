package sh.harold.fulcrum.registry.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * PostgreSQL sink for registry metadata snapshots.
 */
public final class PostgresRegistryNodeSnapshotStore implements RegistryNodeSnapshotStore {
    private final HikariDataSource dataSource;
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
        config.setPoolName(poolName == null || poolName.isBlank() ? "FulcrumRegistryPostgresPool" : poolName);
        config.setMaximumPoolSize(intProperty(properties, "maximum-pool-size", 2));
        config.setMinimumIdle(intProperty(properties, "minimum-idle", 0));
        config.setConnectionTimeout(longProperty(properties, "connection-timeout", 5000L));
        config.setIdleTimeout(longProperty(properties, "idle-timeout", 300000L));
        config.setMaxLifetime(longProperty(properties, "max-lifetime", 1800000L));
        this.dataSource = new HikariDataSource(config);
        ensureTable();
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE registry_node_snapshots
                 SET state = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE node_id = ? AND node_type = ?
                 """)) {
            statement.setString(1, state == null || state.isBlank() ? "OFFLINE" : state);
            statement.setString(2, nodeId);
            statement.setString(3, nodeType == null || nodeType.isBlank() ? "UNKNOWN" : nodeType);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to mark registry node offline: " + nodeId, exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO registry_node_snapshots (
                     node_id, node_type, address, port, role, state, capacity, metadata, updated_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
                 ON CONFLICT (node_id) DO UPDATE SET
                     node_type = EXCLUDED.node_type,
                     address = EXCLUDED.address,
                     port = EXCLUDED.port,
                     role = EXCLUDED.role,
                     state = EXCLUDED.state,
                     capacity = EXCLUDED.capacity,
                     metadata = EXCLUDED.metadata,
                     updated_at = CURRENT_TIMESTAMP
                 """)) {
            statement.setString(1, nodeId);
            statement.setString(2, nodeType);
            statement.setString(3, address);
            statement.setInt(4, port);
            statement.setString(5, role == null || role.isBlank() ? "default" : role);
            statement.setString(6, state);
            statement.setInt(7, capacity);
            statement.setString(8, objectMapper.writeValueAsString(metadata != null ? metadata : Map.of()));
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to snapshot registry node: " + nodeId, exception);
        }
    }

    private void ensureTable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS registry_node_snapshots (
                    node_id TEXT PRIMARY KEY,
                    node_type TEXT NOT NULL,
                    address TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    state TEXT NOT NULL,
                    capacity INTEGER NOT NULL DEFAULT 0,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize registry_node_snapshots table", exception);
        }
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
