package sh.harold.fulcrum.minigame.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists aggregate match summaries and event logs.
 */
public final class MatchLogWriter {

    private static final String INSERT_SQL = """
            INSERT INTO match_log (match_id, family, variant, map_id, environment, slot_id, events, started_at, ended_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (match_id) DO UPDATE SET
                family = EXCLUDED.family,
                variant = EXCLUDED.variant,
                map_id = EXCLUDED.map_id,
                environment = EXCLUDED.environment,
                slot_id = EXCLUDED.slot_id,
                events = EXCLUDED.events,
                started_at = EXCLUDED.started_at,
                ended_at = EXCLUDED.ended_at
            """;

    private final PostgresConnectionAdapter adapter;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    public MatchLogWriter(PostgresConnectionAdapter adapter, Logger logger) {
        this.adapter = adapter;
        this.logger = logger != null ? logger : Logger.getLogger(MatchLogWriter.class.getName());
    }

    public void recordMatch(UUID matchId,
                            String family,
                            String variant,
                            String mapId,
                            String environment,
                            String slotId,
                            long startedAt,
                            long endedAt,
                            List<Event> events) {
        if (matchId == null) {
            return;
        }
        List<Event> safeEvents = events != null ? List.copyOf(events) : List.of();

        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setObject(1, matchId);
            statement.setString(2, family);
            statement.setString(3, variant);
            statement.setString(4, mapId);
            statement.setString(5, environment);
            statement.setString(6, slotId);
            statement.setString(7, toJson(safeEvents));
            statement.setLong(8, startedAt);
            statement.setLong(9, endedAt);
            statement.executeUpdate();
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to record match log for " + matchId, exception);
        }
    }

    private String toJson(Object value) throws Exception {
        return mapper.writeValueAsString(value != null ? value : Collections.emptyList());
    }

    public record Event(long timestamp, String type, UUID actor, UUID target, Map<String, Object> details) {
            public Event(long timestamp, String type, UUID actor, UUID target, Map<String, Object> details) {
                this.timestamp = timestamp;
                this.type = type;
                this.actor = actor;
                this.target = target;
                this.details = details != null ? Map.copyOf(details) : Map.of();
            }
        }
}
