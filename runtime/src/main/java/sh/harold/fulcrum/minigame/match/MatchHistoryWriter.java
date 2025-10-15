package sh.harold.fulcrum.minigame.match;

import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists match participation rows to the {@code player_match_history} table.
 */
public final class MatchHistoryWriter {

    private static final String INSERT_PARTICIPANT_SQL = """
            INSERT INTO match_participants (match_id, player_uuid, session_id, state, respawn_allowed)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (match_id, player_uuid) DO UPDATE
            SET session_id = EXCLUDED.session_id,
                state = EXCLUDED.state,
                respawn_allowed = EXCLUDED.respawn_allowed
            """;

    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO player_match_history (match_id, player_uuid, session_id, recorded_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (match_id, player_uuid) DO UPDATE
            SET session_id = EXCLUDED.session_id,
                recorded_at = EXCLUDED.recorded_at
            """;

    private final PostgresConnectionAdapter adapter;
    private final Logger logger;

    public MatchHistoryWriter(PostgresConnectionAdapter adapter, Logger logger) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.logger = logger != null ? logger : Logger.getLogger(MatchHistoryWriter.class.getName());
    }

    public void recordMatch(UUID matchId,
                            long recordedAt,
                            Collection<Participant> participants) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            return;
        }

        try (Connection connection = adapter.getConnection();
             PreparedStatement participantStatement = connection.prepareStatement(INSERT_PARTICIPANT_SQL);
             PreparedStatement historyStatement = connection.prepareStatement(INSERT_HISTORY_SQL)) {
            for (Participant participant : participants) {
                participantStatement.setObject(1, matchId);
                participantStatement.setObject(2, participant.playerId());
                participantStatement.setObject(3, participant.sessionId());
                participantStatement.setString(4, participant.state());
                participantStatement.setObject(5, participant.respawnAllowed());
                participantStatement.addBatch();

                historyStatement.setObject(1, matchId);
                historyStatement.setObject(2, participant.playerId());
                historyStatement.setObject(3, participant.sessionId());
                historyStatement.setLong(4, recordedAt);
                historyStatement.addBatch();
            }
            participantStatement.executeBatch();
            historyStatement.executeBatch();
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Failed to record match history for " + matchId, exception);
        }
    }

    public record Participant(UUID playerId,
                              UUID sessionId,
                              String state,
                              Boolean respawnAllowed) {
    }
}
