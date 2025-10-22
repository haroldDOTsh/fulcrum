package sh.harold.fulcrum.fundamentals.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
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
        String insert = "INSERT INTO " + TABLE_NAME + " (session_id, player_uuid, environment, family, variant, started_at, ended_at, client_protocol_version, client_brand) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (session_id) DO UPDATE SET " +
                "environment = EXCLUDED.environment, " +
                "family = EXCLUDED.family, " +
                "variant = EXCLUDED.variant, " +
                "ended_at = EXCLUDED.ended_at, " +
                "client_protocol_version = EXCLUDED.client_protocol_version, " +
                "client_brand = EXCLUDED.client_brand";

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
            Integer protocolVersion = record.getClientProtocolVersion();
            if (protocolVersion != null) {
                ps.setInt(8, protocolVersion);
            } else {
                ps.setNull(8, Types.INTEGER);
            }
            ps.setString(9, record.getClientBrand());
            ps.executeUpdate();

            persistSegments(connection, record, sessionContext);
            linkSessionToMatches(connection, record);
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

    private void linkSessionToMatches(Connection connection, PlayerSessionRecord record) {
        Set<UUID> matchIds = collectMatchIds(record);
        if (matchIds.isEmpty()) {
            return;
        }

        UUID sessionUuid;
        try {
            sessionUuid = UUID.fromString(record.getSessionId());
        } catch (Exception ex) {
            logger.log(Level.FINE, "Skipping session link; invalid session id '" + record.getSessionId() + "'", ex);
            return;
        }

        String updateParticipants = "UPDATE match_participants SET session_id = ? " +
                "WHERE match_id = ? AND player_uuid = ? AND (session_id IS NULL OR session_id <> ?)";
        String updateHistory = "UPDATE player_match_history SET session_id = ?, recorded_at = recorded_at " +
                "WHERE match_id = ? AND player_uuid = ? AND (session_id IS NULL OR session_id <> ?)";

        try (PreparedStatement participantsPs = connection.prepareStatement(updateParticipants);
             PreparedStatement historyPs = connection.prepareStatement(updateHistory)) {
            for (UUID matchId : matchIds) {
                participantsPs.setObject(1, sessionUuid);
                participantsPs.setObject(2, matchId);
                participantsPs.setObject(3, record.getPlayerId());
                participantsPs.setObject(4, sessionUuid);
                participantsPs.addBatch();

                historyPs.setObject(1, sessionUuid);
                historyPs.setObject(2, matchId);
                historyPs.setObject(3, record.getPlayerId());
                historyPs.setObject(4, sessionUuid);
                historyPs.addBatch();
            }
            participantsPs.executeBatch();
            historyPs.executeBatch();
        } catch (SQLException ex) {
            logger.log(Level.FINE, "Failed to backfill session links for session " + record.getSessionId(), ex);
        }
    }

    private Set<UUID> collectMatchIds(PlayerSessionRecord record) {
        Set<UUID> matchIds = new LinkedHashSet<>();

        Object lastMatchId = record.getMinigames().get("lastMatchId");
        parseMatchId(lastMatchId).ifPresent(matchIds::add);

        for (PlayerSessionRecord.Segment segment : record.getSegments()) {
            Object raw = segment.getMetadata().get("matchId");
            parseMatchId(raw).ifPresent(matchIds::add);
        }

        return matchIds;
    }

    private Optional<UUID> parseMatchId(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString();
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text.trim()));
        } catch (IllegalArgumentException ex) {
            logger.log(Level.FINE, "Ignoring invalid match id '" + text + "' while linking sessions", ex);
            return Optional.empty();
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
