package sh.harold.fulcrum.registry.session;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;

class SessionLogRepository implements AutoCloseable {

    private static final String TABLE_NAME = "player_sessions";

    private final PostgresConnectionAdapter adapter;
    private final Logger logger;

    SessionLogRepository(PostgresConnectionAdapter adapter, Logger logger) {
        this.adapter = adapter;
        this.logger = logger;
    }

    void recordSession(PlayerSessionRecord record, long endedAt) {
        String insert = "INSERT INTO " + TABLE_NAME + " (session_id, player_uuid, environment, family, variant, started_at, ended_at, client_protocol_version, client_brand) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (session_id) DO UPDATE SET " +
                "environment = EXCLUDED.environment, " +
                "family = EXCLUDED.family, " +
                "variant = EXCLUDED.variant, " +
                "ended_at = EXCLUDED.ended_at, " +
                "client_protocol_version = EXCLUDED.client_protocol_version, " +
                "client_brand = EXCLUDED.client_brand";

        SessionContext context = resolveSessionContext(record);

        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(insert)) {
            ps.setObject(1, UUID.fromString(record.getSessionId()));
            ps.setObject(2, record.getPlayerId());
            ps.setString(3, context.environment());
            ps.setString(4, context.family());
            ps.setString(5, context.variant());
            ps.setLong(6, record.getCreatedAt());
            ps.setLong(7, endedAt);
            Integer protocolVersion = record.getClientProtocolVersion();
            if (protocolVersion != null) {
                ps.setInt(8, protocolVersion);
            } else {
                ps.setNull(8, Types.INTEGER);
            }
            ps.setString(9, record.getClientBrand());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to persist session summary for {}", record.getPlayerId(), e);
        }
    }

    private SessionContext resolveSessionContext(PlayerSessionRecord record) {
        String environment = stringValue(record.getCore().get("environment"));
        String family = null;
        String variant = null;

        Object active = record.getMinigames().get("active");
        if (active instanceof Map<?, ?> activeMap) {
            if (environment == null) {
                environment = stringValue(activeMap.get("environment"));
            }
            family = stringValue(activeMap.get("family"));
            variant = stringValue(activeMap.get("variant"));
        }

        if ((family == null || variant == null || environment == null) && !record.getSegments().isEmpty()) {
            for (int i = record.getSegments().size() - 1; i >= 0; i--) {
                Map<String, Object> metadata = record.getSegments().get(i).getMetadata();
                if (metadata == null || metadata.isEmpty()) {
                    continue;
                }
                if (environment == null) {
                    environment = stringValue(metadata.get("environment"));
                }
                if (family == null) {
                    family = stringValue(metadata.get("family"));
                }
                if (variant == null) {
                    variant = stringValue(metadata.get("variant"));
                }
                if (environment != null && family != null && variant != null) {
                    break;
                }
            }
        }

        return new SessionContext(environment, family, variant);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    @Override
    public void close() {
        try {
            adapter.close();
        } catch (Exception ignored) {
        }
    }

    private record SessionContext(String environment, String family, String variant) {
    }
}
