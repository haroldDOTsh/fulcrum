package sh.harold.fulcrum.fundamentals.rank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.schema.SchemaDefinition;
import sh.harold.fulcrum.api.data.schema.SchemaRegistry;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;

/**
 * Dedicated repository for persisting rank audit events into PostgreSQL.
 */
final class RankAuditLogRepository {
    private static final String AUDIT_TABLE = "player_rank_audit";

    private final PostgresConnectionAdapter connectionAdapter;
    private final Logger logger;

    RankAuditLogRepository(PostgresConnectionAdapter connectionAdapter, Logger logger) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void initialize(ClassLoader resourceLoader) {
        try {
            SchemaRegistry.ensureSchema(
                connectionAdapter,
                SchemaDefinition.fromResource(
                    "player-rank-audit-001",
                    "Audit log for player rank changes",
                    resourceLoader,
                    "migrations/player_rank_audit.sql"
                )
            );
            logger.info("Rank audit logging enabled (PostgreSQL backend detected)");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize rank audit schema", exception);
        }
    }

    void recordChange(UUID playerId,
                      String playerName,
                      Rank previousPrimary,
                      Rank newPrimary,
                      List<String> allRanks,
                      RankChangeContext context) {
        String sql = "INSERT INTO " + AUDIT_TABLE + " (" +
            "player_uuid, player_name, executor_type, executor_name, executor_uuid, " +
            "main_rank_from, main_rank_to, all_ranks, created_at" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";

        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, playerId);
            if (playerName == null || playerName.isBlank()) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, playerName);
            }

            statement.setString(3, context.executorType().name());
            if (context.executorName() == null || context.executorName().isBlank()) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, context.executorName());
            }

            if (context.executorUuid() != null) {
                statement.setObject(5, context.executorUuid());
            } else {
                statement.setNull(5, Types.OTHER);
            }

            if (previousPrimary != null) {
                statement.setString(6, previousPrimary.name());
            } else {
                statement.setNull(6, Types.VARCHAR);
            }

            if (newPrimary != null) {
                statement.setString(7, newPrimary.name());
            } else {
                statement.setNull(7, Types.VARCHAR);
            }

            java.sql.Array ranksArray = connection.createArrayOf("text", allRanks.toArray(new String[0]));
            try {
                statement.setArray(8, ranksArray);
                statement.executeUpdate();
            } finally {
                try {
                    ranksArray.free();
                } catch (SQLException ignored) {
                    // ignore cleanup failures
                }
            }
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Failed to append rank audit log for " + playerId, exception);
        }
    }
}
