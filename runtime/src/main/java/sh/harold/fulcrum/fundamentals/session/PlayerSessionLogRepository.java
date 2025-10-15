package sh.harold.fulcrum.fundamentals.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists session summaries to PostgreSQL for offline analytics.
 */
public class PlayerSessionLogRepository {

    private static final String TABLE_NAME = "player_sessions";
    private static final String SEGMENT_TABLE_NAME = "player_session_segments";
    private final PostgresConnectionAdapter adapter;
    private final Logger logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlayerSessionLogRepository(PostgresConnectionAdapter adapter, Logger logger) {
        this.adapter = adapter;
        this.logger = logger;
    }

    public void recordSession(PlayerSessionRecord record, long endedAt) {
        String insert = "INSERT INTO " + TABLE_NAME + " (session_id, player_uuid, environment, family, variant, started_at, ended_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (session_id) DO UPDATE SET " +
                "environment = EXCLUDED.environment, " +
                "family = EXCLUDED.family, " +
                "variant = EXCLUDED.variant, " +
                "ended_at = EXCLUDED.ended_at";

        SessionContext sessionContext = resolveSessionContext(record);

        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(insert)) {
            ps.setObject(1, UUID.fromString(record.getSessionId()));
            ps.setObject(2, record.getPlayerId());
            ps.setString(3, sessionContext.environment());
            ps.setString(4, sessionContext.family());
            ps.setString(5, sessionContext.variant());
            ps.setLong(6, record.getCreatedAt());
            ps.setLong(7, endedAt);
            ps.executeUpdate();

            persistSegments(connection, record, sessionContext);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to persist session summary for " + record.getPlayerId(), e);
        }
    }

    private void persistSegments(Connection connection,
                                 PlayerSessionRecord record,
                                 SessionContext sessionContext) {
        String delete = "DELETE FROM " + SEGMENT_TABLE_NAME + " WHERE session_id = ?";
        String insert = "INSERT INTO " + SEGMENT_TABLE_NAME + " (session_id, segment_index, type, context, environment, family, variant, started_at, ended_at, metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ? )";

        try (PreparedStatement deletePs = connection.prepareStatement(delete)) {
            deletePs.setObject(1, UUID.fromString(record.getSessionId()));
            deletePs.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to clear segments for session " + record.getSessionId(), e);
        }

        try (PreparedStatement insertPs = connection.prepareStatement(insert)) {
            int index = 0;
            for (PlayerSessionRecord.Segment segment : record.getSegments()) {
                SessionContext segmentContext = deriveSegmentContext(segment, sessionContext);
                insertPs.setObject(1, UUID.fromString(record.getSessionId()));
                insertPs.setInt(2, index++);
                insertPs.setString(3, segment.getType());
                insertPs.setString(4, segment.getContext());
                insertPs.setString(5, segmentContext.environment());
                insertPs.setString(6, segmentContext.family());
                insertPs.setString(7, segmentContext.variant());
                insertPs.setLong(8, segment.getStartedAt());
                if (segment.getEndedAt() != null) {
                    insertPs.setLong(9, segment.getEndedAt());
                } else {
                    insertPs.setNull(9, Types.BIGINT);
                }
                PGobject metadataJson = new PGobject();
                metadataJson.setType("jsonb");
                metadataJson.setValue(objectMapper.writeValueAsString(segment.getMetadata()));
                insertPs.setObject(10, metadataJson);
                insertPs.addBatch();
            }
            insertPs.executeBatch();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to persist session segments for " + record.getSessionId(), e);
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

        return new SessionContext(environment, family, variant);
    }

    private SessionContext deriveSegmentContext(PlayerSessionRecord.Segment segment, SessionContext fallback) {
        Map<String, Object> metadata = segment.getMetadata();
        String environment = stringValue(metadata.get("environment"));
        String family = stringValue(metadata.get("family"));
        String variant = stringValue(metadata.get("variant"));

        if (environment == null) {
            environment = fallback.environment();
        }
        if (family == null) {
            family = fallback.family();
        }
        if (variant == null) {
            variant = fallback.variant();
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

    private record SessionContext(String environment, String family, String variant) {
    }
}
