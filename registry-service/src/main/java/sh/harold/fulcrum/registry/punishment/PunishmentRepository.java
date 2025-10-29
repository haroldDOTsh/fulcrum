package sh.harold.fulcrum.registry.punishment;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.schema.SchemaDefinition;
import sh.harold.fulcrum.api.data.schema.SchemaRegistry;
import sh.harold.fulcrum.api.punishment.PunishmentEffectType;
import sh.harold.fulcrum.api.punishment.PunishmentLadder;
import sh.harold.fulcrum.api.punishment.PunishmentReason;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Handles persistence of punishment records in PostgreSQL.
 */
public final class PunishmentRepository implements AutoCloseable {

    private final PostgresConnectionAdapter adapter;
    private final Logger logger;

    public PunishmentRepository(PostgresConnectionAdapter adapter, Logger logger) {
        this.adapter = adapter;
        this.logger = logger;
        ensureSchema();
    }

    private void ensureSchema() {
        try {
            SchemaRegistry.ensureSchema(
                    adapter,
                    SchemaDefinition.fromResource(
                            "punishments-001",
                            "Create punishment tables",
                            PunishmentRepository.class.getClassLoader(),
                            "migrations/punishments-001.sql"
                    )
            );
        } catch (Exception ex) {
            logger.warn("Failed to initialize punishment schema: {}", ex.getMessage());
        }
    }

    void savePunishment(PunishmentRecord record) throws SQLException {
        String insertPunishment = """
                INSERT INTO punishments(
                    punishment_id,
                    player_uuid,
                    player_name,
                    staff_uuid,
                    staff_name,
                    ladder,
                    reason,
                    rung_before,
                    rung_after,
                    created_at,
                    expires_at,
                    status,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String insertEffect = """
                INSERT INTO punishment_effects(
                    punishment_id,
                    effect_order,
                    type,
                    duration_seconds,
                    expires_at,
                    message
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = adapter.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement punishmentStmt = connection.prepareStatement(insertPunishment);
                 PreparedStatement effectStmt = connection.prepareStatement(insertEffect)) {

                Instant expiresAt = computeMaxExpiry(record);

                punishmentStmt.setObject(1, record.getPunishmentId());
                punishmentStmt.setObject(2, record.getPlayerId());
                punishmentStmt.setString(3, record.getPlayerName());
                punishmentStmt.setObject(4, record.getStaffId());
                punishmentStmt.setString(5, record.getStaffName());
                punishmentStmt.setString(6, record.getLadder().name());
                punishmentStmt.setString(7, record.getReason().getId());
                punishmentStmt.setInt(8, record.getRungBefore());
                punishmentStmt.setInt(9, record.getRungAfter());
                punishmentStmt.setTimestamp(10, Timestamp.from(record.getIssuedAt()));
                if (expiresAt != null) {
                    punishmentStmt.setTimestamp(11, Timestamp.from(expiresAt));
                } else {
                    punishmentStmt.setNull(11, Types.TIMESTAMP);
                }
                punishmentStmt.setString(12, record.getStatus().name());
                punishmentStmt.setTimestamp(13, Timestamp.from(record.getUpdatedAt()));
                punishmentStmt.executeUpdate();

                int order = 0;
                for (PunishmentRecordEffect effect : record.getEffects()) {
                    effectStmt.setObject(1, record.getPunishmentId());
                    effectStmt.setInt(2, order++);
                    effectStmt.setString(3, effect.type().name());
                    if (effect.duration() != null) {
                        effectStmt.setLong(4, effect.duration().getSeconds());
                    } else {
                        effectStmt.setNull(4, Types.BIGINT);
                    }
                    if (effect.expiresAt() != null) {
                        effectStmt.setTimestamp(5, Timestamp.from(effect.expiresAt()));
                    } else {
                        effectStmt.setNull(5, Types.TIMESTAMP);
                    }
                    effectStmt.setString(6, effect.message());
                    effectStmt.addBatch();
                }
                effectStmt.executeBatch();

                upsertLadderState(connection, record.getPlayerId(), record.getLadder(), record.getRungAfter(), record.getIssuedAt());

                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    void updateStatus(UUID punishmentId, PunishmentStatus status, Instant updatedAt,
                      UUID playerId, PunishmentLadder ladder) {
        String update = "UPDATE punishments SET status = ?, updated_at = ? WHERE punishment_id = ?";
        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(update)) {
            ps.setString(1, status.name());
            ps.setTimestamp(2, Timestamp.from(updatedAt));
            ps.setObject(3, punishmentId);
            ps.executeUpdate();

            int rung = countRelevantRung(connection, playerId, ladder);
            upsertLadderState(connection, playerId, ladder, rung, updatedAt);
        } catch (SQLException ex) {
            logger.warn("Failed to update punishment status: {}", ex.getMessage());
        }
    }

    Optional<PunishmentRecord> loadPunishment(UUID punishmentId) {
        String query = """
                SELECT punishment_id, player_uuid, player_name, staff_uuid, staff_name, ladder, reason,
                       rung_before, rung_after, created_at, expires_at, status, updated_at
                FROM punishments
                WHERE punishment_id = ?
                """;
        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setObject(1, punishmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PunishmentRecord record = mapRecord(connection, rs);
                    return Optional.ofNullable(record);
                }
            }
        } catch (SQLException ex) {
            logger.warn("Failed to load punishment {}: {}", punishmentId, ex.getMessage());
        }
        return Optional.empty();
    }

    List<PunishmentRecord> loadActivePunishments(UUID playerId) {
        String query = """
                SELECT punishment_id, player_uuid, player_name, staff_uuid, staff_name, ladder, reason,
                       rung_before, rung_after, created_at, expires_at, status, updated_at
                FROM punishments
                WHERE player_uuid = ? AND status = ?
                ORDER BY created_at ASC
                """;
        List<PunishmentRecord> records = new ArrayList<>();
        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setObject(1, playerId);
            ps.setString(2, PunishmentStatus.ACTIVE.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PunishmentRecord record = mapRecord(connection, rs);
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
        } catch (SQLException ex) {
            logger.warn("Failed to load active punishments: {}", ex.getMessage());
        }
        return records;
    }

    List<PunishmentRecord> loadPunishmentHistory(UUID playerId) {
        String query = """
                SELECT punishment_id, player_uuid, player_name, staff_uuid, staff_name, ladder, reason,
                       rung_before, rung_after, created_at, expires_at, status, updated_at
                FROM punishments
                WHERE player_uuid = ?
                ORDER BY created_at ASC
                """;
        List<PunishmentRecord> records = new ArrayList<>();
        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setObject(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PunishmentRecord record = mapRecord(connection, rs);
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
        } catch (SQLException ex) {
            logger.warn("Failed to load punishment history: {}", ex.getMessage());
        }
        return records;
    }

    List<UUID> loadPunishmentHistoryIds(UUID playerId) {
        String query = """
                SELECT punishment_id
                FROM punishments
                WHERE player_uuid = ?
                ORDER BY created_at ASC
                """;
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setObject(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
        } catch (SQLException ex) {
            logger.warn("Failed to load punishment history ids: {}", ex.getMessage());
        }
        return ids;
    }

    Map<PunishmentLadder, Integer> loadLadderSnapshot(UUID playerId) {
        String query = "SELECT ladder, rung FROM ladder_state WHERE player_uuid = ?";
        Map<PunishmentLadder, Integer> snapshot = new EnumMap<>(PunishmentLadder.class);
        try (Connection connection = adapter.getConnection();
             PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setObject(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ladderId = rs.getString("ladder");
                    int rung = rs.getInt("rung");
                    try {
                        PunishmentLadder ladder = PunishmentLadder.valueOf(ladderId);
                        snapshot.put(ladder, rung);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (SQLException ex) {
            logger.warn("Failed to load ladder snapshot: {}", ex.getMessage());
        }
        return snapshot;
    }

    private List<PunishmentRecordEffect> loadEffects(Connection connection, UUID punishmentId) throws SQLException {
        String query = """
                SELECT type, duration_seconds, expires_at, message
                FROM punishment_effects
                WHERE punishment_id = ?
                ORDER BY effect_order ASC
                """;
        List<PunishmentRecordEffect> effects = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setObject(1, punishmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PunishmentEffectType type = PunishmentEffectType.valueOf(rs.getString("type"));
                    Long durationSeconds = rs.getObject("duration_seconds", Long.class);
                    Timestamp expiresAtTs = rs.getTimestamp("expires_at");
                    Instant expiresAt = expiresAtTs != null ? expiresAtTs.toInstant() : null;
                    String message = rs.getString("message");
                    effects.add(new PunishmentRecordEffect(
                            type,
                            durationSeconds != null ? Duration.ofSeconds(durationSeconds) : null,
                            expiresAt,
                            message
                    ));
                }
            }
        }
        return effects;
    }

    private void upsertLadderState(Connection connection, UUID playerId, PunishmentLadder ladder, int rung, Instant updatedAt) throws SQLException {
        if (rung <= 0) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM ladder_state WHERE player_uuid = ? AND ladder = ?")) {
                delete.setObject(1, playerId);
                delete.setString(2, ladder.name());
                delete.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ladder_state(player_uuid, ladder, rung, updated_at) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT (player_uuid, ladder) DO UPDATE SET rung = EXCLUDED.rung, updated_at = EXCLUDED.updated_at")) {
            ps.setObject(1, playerId);
            ps.setString(2, ladder.name());
            ps.setInt(3, rung);
            ps.setTimestamp(4, Timestamp.from(updatedAt));
            ps.executeUpdate();
        }
    }

    int countRelevantRung(UUID playerId, PunishmentLadder ladder) {
        try (Connection connection = adapter.getConnection()) {
            return countRelevantRung(connection, playerId, ladder);
        } catch (SQLException ex) {
            logger.warn("Failed to count active rung: {}", ex.getMessage());
            return 0;
        }
    }

    private int countRelevantRung(Connection connection, UUID playerId, PunishmentLadder ladder) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM punishments WHERE player_uuid = ? AND ladder = ? AND status IN (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(countSql)) {
            ps.setObject(1, playerId);
            ps.setString(2, ladder.name());
            ps.setString(3, PunishmentStatus.ACTIVE.name());
            ps.setString(4, PunishmentStatus.EXPIRED.name());
            ps.setString(5, PunishmentStatus.PARDONED.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private Instant computeMaxExpiry(PunishmentRecord record) {
        Instant max = null;
        for (PunishmentRecordEffect effect : record.getEffects()) {
            if (effect.expiresAt() != null) {
                if (max == null || effect.expiresAt().isAfter(max)) {
                    max = effect.expiresAt();
                }
            }
        }
        return max;
    }

    private PunishmentRecord mapRecord(Connection connection, ResultSet rs) throws SQLException {
        UUID punishmentId = rs.getObject("punishment_id", UUID.class);
        UUID playerId = rs.getObject("player_uuid", UUID.class);
        String playerName = rs.getString("player_name");
        UUID staffId = rs.getObject("staff_uuid", UUID.class);
        String staffName = rs.getString("staff_name");
        String ladderId = rs.getString("ladder");
        String reasonId = rs.getString("reason");
        PunishmentReason reason = PunishmentReason.fromId(reasonId);
        PunishmentLadder ladder = null;
        try {
            ladder = PunishmentLadder.valueOf(ladderId);
        } catch (Exception ignored) {
        }
        if (reason == null || ladder == null) {
            logger.warn("Skipping punishment {} due to unknown reason/ladder.", punishmentId);
            return null;
        }
        int rungBefore = rs.getInt("rung_before");
        int rungAfter = rs.getInt("rung_after");
        Instant issuedAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        PunishmentStatus status = PunishmentStatus.valueOf(rs.getString("status"));

        List<PunishmentRecordEffect> effects = loadEffects(connection, punishmentId);
        PunishmentRecord record = new PunishmentRecord(
                punishmentId,
                playerId,
                playerName,
                reason,
                ladder,
                rungBefore,
                rungAfter,
                staffId,
                staffName,
                issuedAt,
                effects
        );
        record.setStatus(status, updatedAt);
        return record;
    }

    @Override
    public void close() {
        try {
            adapter.close();
        } catch (Exception ignored) {
        }
    }
}
