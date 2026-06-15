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
 * PostgreSQL-backed command consumer cursor for authority worker recovery.
 */
public final class PostgresAuthorityCommandConsumerCursorStore
    implements AuthorityCommandConsumerCursorStore {
    private static final String REQUIRED_TABLE = "authority_command_consumer_cursors";

    private final PostgresConnectionAdapter connectionAdapter;

    public PostgresAuthorityCommandConsumerCursorStore(PostgresConnectionAdapter connectionAdapter) {
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
            throw new IllegalStateException("Failed to validate authority command consumer cursor table", exception);
        }
    }

    @Override
    public Optional<Cursor> cursor(String commandDomain, int partition) {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT command_topic, committed_offset, partition_key, last_command_id,
                        last_result_revision, last_result_accepted, last_rejection_reason,
                        writer_claim_id, writer_claim_epoch, writer_claim_fingerprint,
                        owner_node, updated_at
                 FROM authority_command_consumer_cursors
                 WHERE command_domain = ? AND command_partition = ?
                 """)) {
            statement.setString(1, commandDomain);
            statement.setInt(2, partition);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                Timestamp updatedAt = resultSet.getTimestamp("updated_at");
                return Optional.of(new Cursor(
                    commandDomain,
                    resultSet.getString("command_topic"),
                    partition,
                    resultSet.getLong("committed_offset"),
                    resultSet.getString("partition_key"),
                    resultSet.getObject("last_command_id", UUID.class),
                    resultSet.getLong("last_result_revision"),
                    resultSet.getBoolean("last_result_accepted"),
                    resultSet.getString("last_rejection_reason"),
                    resultSet.getObject("writer_claim_id", UUID.class),
                    resultSet.getLong("writer_claim_epoch"),
                    resultSet.getString("writer_claim_fingerprint"),
                    resultSet.getString("owner_node"),
                    updatedAt == null ? 0L : updatedAt.toInstant().toEpochMilli()
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read authority command consumer cursor", exception);
        }
    }

    @Override
    public void recordApplied(AuthorityLogCommandWorker.PartitionResult result) {
        AuthorityCommandConsumerCursorStore.cursorFor(result).ifPresent(this::upsertCursor);
    }

    private void upsertCursor(Cursor cursor) {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO authority_command_consumer_cursors (
                     command_domain, command_partition, command_topic, committed_offset,
                     partition_key, last_command_id, last_result_revision, last_result_accepted,
                     last_rejection_reason, writer_claim_id, writer_claim_epoch,
                     writer_claim_fingerprint, owner_node, updated_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT (command_domain, command_partition) DO UPDATE
                 SET command_topic = EXCLUDED.command_topic,
                     committed_offset = EXCLUDED.committed_offset,
                     partition_key = EXCLUDED.partition_key,
                     last_command_id = EXCLUDED.last_command_id,
                     last_result_revision = EXCLUDED.last_result_revision,
                     last_result_accepted = EXCLUDED.last_result_accepted,
                     last_rejection_reason = EXCLUDED.last_rejection_reason,
                     writer_claim_id = EXCLUDED.writer_claim_id,
                     writer_claim_epoch = EXCLUDED.writer_claim_epoch,
                     writer_claim_fingerprint = EXCLUDED.writer_claim_fingerprint,
                     owner_node = EXCLUDED.owner_node,
                     updated_at = EXCLUDED.updated_at
                 WHERE authority_command_consumer_cursors.committed_offset < EXCLUDED.committed_offset
                 """)) {
            bindCursor(statement, cursor);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record authority command consumer cursor", exception);
        }
    }

    private static void bindCursor(PreparedStatement statement, Cursor cursor) throws SQLException {
        Instant updatedAt = Instant.ofEpochMilli(cursor.updatedAtEpochMillis());
        statement.setString(1, cursor.commandDomain());
        statement.setInt(2, cursor.partition());
        statement.setString(3, cursor.commandTopic());
        statement.setLong(4, cursor.committedOffset());
        statement.setString(5, cursor.partitionKey());
        statement.setObject(6, cursor.lastCommandId());
        statement.setLong(7, cursor.lastResultRevision());
        statement.setBoolean(8, cursor.lastResultAccepted());
        statement.setString(9, cursor.lastRejectionReason());
        statement.setObject(10, cursor.writerClaimId());
        statement.setLong(11, cursor.writerClaimEpoch());
        statement.setString(12, cursor.writerClaimFingerprint());
        statement.setString(13, cursor.ownerNode());
        statement.setTimestamp(14, Timestamp.from(updatedAt));
    }
}
