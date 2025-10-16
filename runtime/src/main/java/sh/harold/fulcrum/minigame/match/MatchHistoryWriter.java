package sh.harold.fulcrum.minigame.match;

import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.*;
import java.util.*;
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
            Set<UUID> missingSessionIds = findMissingSessionIds(connection, participants);
            if (!missingSessionIds.isEmpty()) {
                logger.fine(() -> "Skipping " + missingSessionIds.size() + " session references for match " + matchId);
            }

            for (Participant participant : participants) {
                UUID sessionId = participant.sessionId();
                boolean includeSession = sessionId != null && !missingSessionIds.contains(sessionId);

                participantStatement.setObject(1, matchId);
                participantStatement.setObject(2, participant.playerId());
                if (includeSession) {
                    participantStatement.setObject(3, sessionId);
                } else {
                    participantStatement.setNull(3, Types.OTHER);
                }
                participantStatement.setString(4, participant.state());
                participantStatement.setObject(5, participant.respawnAllowed());
                participantStatement.addBatch();

                historyStatement.setObject(1, matchId);
                historyStatement.setObject(2, participant.playerId());
                if (includeSession) {
                    historyStatement.setObject(3, sessionId);
                } else {
                    historyStatement.setNull(3, Types.OTHER);
                }
                historyStatement.setLong(4, recordedAt);
                historyStatement.addBatch();
            }
            participantStatement.executeBatch();
            historyStatement.executeBatch();
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Failed to record match history for " + matchId, exception);
        }
    }

    private Set<UUID> findMissingSessionIds(Connection connection, Collection<Participant> participants) throws SQLException {
        Set<UUID> candidates = new LinkedHashSet<>();
        for (Participant participant : participants) {
            UUID sessionId = participant.sessionId();
            if (sessionId != null) {
                candidates.add(sessionId);
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }

        StringBuilder sql = new StringBuilder("SELECT session_id FROM player_sessions WHERE session_id IN (");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');

        Set<UUID> existing = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (UUID sessionId : candidates) {
                ps.setObject(index++, sessionId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID sessionId = rs.getObject(1, UUID.class);
                    if (sessionId != null) {
                        existing.add(sessionId);
                    }
                }
            }
        }

        Set<UUID> missing = new HashSet<>(candidates);
        missing.removeAll(existing);
        return missing;
    }

    public record Participant(UUID playerId,
                              UUID sessionId,
                              String state,
                              Boolean respawnAllowed) {
    }
}
