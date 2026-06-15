package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed state projection cursor for compacted topic recovery.
 */
public final class PostgresAuthorityStateProjectionCursorStore
    implements AuthorityStateProjectionCursorStore {
    private static final String REQUIRED_TABLE = "authority_state_projection_cursors";

    private final PostgresConnectionAdapter connectionAdapter;

    public PostgresAuthorityStateProjectionCursorStore(PostgresConnectionAdapter connectionAdapter) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
    }

    public void validateSchema() {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            statement.setString(1, REQUIRED_TABLE);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getString(1) == null) {
                    throw new IllegalStateException(
                        "Missing required " + REQUIRED_TABLE + " table. Run data-api migrations before startup."
                    );
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority state projection cursor table", exception);
        }
    }

    @Override
    public Optional<Cursor> cursor(
        String projectionName,
        String projectionVersion,
        String commandDomain,
        int partition
    ) {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT state_topic, committed_offset, partition_key, last_command_id,
                        last_event_id, last_revision, last_restore_applied,
                        last_restore_message, updated_at
                 FROM authority_state_projection_cursors
                 WHERE projection_name = ?
                   AND projection_version = ?
                   AND command_domain = ?
                   AND state_partition = ?
                 """)) {
            statement.setString(1, projectionName);
            statement.setString(2, projectionVersion);
            statement.setString(3, commandDomain);
            statement.setInt(4, partition);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                Timestamp updatedAt = resultSet.getTimestamp("updated_at");
                return Optional.of(new Cursor(
                    projectionName,
                    projectionVersion,
                    commandDomain,
                    resultSet.getString("state_topic"),
                    partition,
                    resultSet.getLong("committed_offset"),
                    resultSet.getString("partition_key"),
                    resultSet.getObject("last_command_id", UUID.class),
                    resultSet.getObject("last_event_id", UUID.class),
                    resultSet.getLong("last_revision"),
                    resultSet.getBoolean("last_restore_applied"),
                    resultSet.getString("last_restore_message"),
                    updatedAt == null ? 0L : updatedAt.toInstant().toEpochMilli()
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read authority state projection cursor", exception);
        }
    }

    @Override
    public void recordApplied(AuthorityStateProjectionWorker.PartitionResult result) {
        AuthorityStateProjectionCursorStore.cursorFor(result).ifPresent(this::upsertCursor);
    }

    private void upsertCursor(Cursor cursor) {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO authority_state_projection_cursors (
                     projection_name, projection_version, command_domain, state_topic,
                     state_partition, committed_offset, partition_key, last_command_id,
                     last_event_id, last_revision, last_restore_applied,
                     last_restore_message, updated_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT (projection_name, projection_version, command_domain, state_partition) DO UPDATE
                 SET state_topic = EXCLUDED.state_topic,
                     committed_offset = EXCLUDED.committed_offset,
                     partition_key = EXCLUDED.partition_key,
                     last_command_id = EXCLUDED.last_command_id,
                     last_event_id = EXCLUDED.last_event_id,
                     last_revision = EXCLUDED.last_revision,
                     last_restore_applied = EXCLUDED.last_restore_applied,
                     last_restore_message = EXCLUDED.last_restore_message,
                     updated_at = EXCLUDED.updated_at
                 WHERE authority_state_projection_cursors.committed_offset < EXCLUDED.committed_offset
                 """)) {
            bindCursor(statement, cursor);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record authority state projection cursor", exception);
        }
    }

    private static void bindCursor(PreparedStatement statement, Cursor cursor) throws SQLException {
        Instant updatedAt = Instant.ofEpochMilli(cursor.updatedAtEpochMillis());
        statement.setString(1, cursor.projectionName());
        statement.setString(2, cursor.projectionVersion());
        statement.setString(3, cursor.commandDomain());
        statement.setString(4, cursor.stateTopic());
        statement.setInt(5, cursor.partition());
        statement.setLong(6, cursor.committedOffset());
        statement.setString(7, cursor.partitionKey());
        statement.setObject(8, cursor.lastCommandId());
        statement.setObject(9, cursor.lastEventId());
        statement.setLong(10, cursor.lastRevision());
        statement.setBoolean(11, cursor.lastRestoreApplied());
        statement.setString(12, cursor.lastRestoreMessage());
        statement.setTimestamp(13, Timestamp.from(updatedAt));
    }
}
