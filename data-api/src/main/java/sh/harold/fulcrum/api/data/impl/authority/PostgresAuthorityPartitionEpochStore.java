package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL-backed ownership record for authority fencing epochs.
 */
public final class PostgresAuthorityPartitionEpochStore
    implements AuthorityFencingCommandPort.PartitionEpochStore {
    private static final List<String> REQUIRED_TABLES = List.of(
        "authority_partition_epochs",
        "authority_writer_claims"
    );

    private final PostgresConnectionAdapter connectionAdapter;

    public PostgresAuthorityPartitionEpochStore(PostgresConnectionAdapter connectionAdapter) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
    }

    public void validateSchema() {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : REQUIRED_TABLES) {
                statement.setString(1, table);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || resultSet.getString(1) == null) {
                        throw new IllegalStateException(
                            "Missing required " + table + " table. Run data-api migrations before startup."
                        );
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority partition epoch tables", exception);
        }
    }

    @Override
    public AuthorityWriterClaim claimEpoch(
        String commandDomain,
        String commandTopic,
        String partitionKey,
        String ownerNode
    ) {
        if (commandDomain == null || commandDomain.isBlank()) {
            throw new IllegalArgumentException("commandDomain is required");
        }
        if (commandTopic == null || commandTopic.isBlank()) {
            throw new IllegalArgumentException("commandTopic is required");
        }
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey is required");
        }
        if (ownerNode == null || ownerNode.isBlank()) {
            throw new IllegalArgumentException("ownerNode is required");
        }
        try (Connection connection = connectionAdapter.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ExistingEpoch existing = existingEpoch(connection, commandDomain, partitionKey);
                long epoch = existing == null
                    ? 1L
                    : ownerNode.equals(existing.ownerNode()) ? existing.epoch() : existing.epoch() + 1L;
                String previousOwnerNode = existing == null ? null : existing.ownerNode();
                long previousEpoch = existing == null ? 0L : existing.epoch();
                AuthorityWriterClaim claim = AuthorityWriterClaim.mint(
                    commandDomain,
                    commandTopic,
                    partitionKey,
                    ownerNode,
                    epoch,
                    previousOwnerNode,
                    previousEpoch,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS)
                );
                if (existing == null) {
                    insertEpoch(connection, claim);
                } else {
                    Instant claimedAt = ownerNode.equals(existing.ownerNode())
                        ? existing.claimedAt()
                        : claim.claimedAt();
                    updateEpoch(connection, claim, claimedAt);
                }
                insertClaim(connection, claim);
                connection.commit();
                return claim;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof SQLException sqlException) {
                    throw new IllegalStateException("Failed to claim authority partition epoch", sqlException);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to claim authority partition epoch", exception);
        }
    }

    private ExistingEpoch existingEpoch(Connection connection, String commandDomain, String partitionKey)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT command_topic, owner_node, epoch, claimed_at
            FROM authority_partition_epochs
            WHERE command_domain = ? AND partition_key = ?
            FOR UPDATE
            """)) {
            statement.setString(1, commandDomain);
            statement.setString(2, partitionKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Timestamp claimedAt = resultSet.getTimestamp("claimed_at");
                return new ExistingEpoch(
                    resultSet.getString("command_topic"),
                    resultSet.getString("owner_node"),
                    resultSet.getLong("epoch"),
                    claimedAt == null ? Instant.EPOCH : claimedAt.toInstant()
                );
            }
        }
    }

    private void insertEpoch(Connection connection, AuthorityWriterClaim claim) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_partition_epochs (
                command_domain, partition_key, command_topic, owner_node,
                epoch, claimed_at, updated_at, last_claim_id, last_claim_fingerprint
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, claim.commandDomain());
            statement.setString(2, claim.partitionKey());
            statement.setString(3, claim.commandTopic());
            statement.setString(4, claim.ownerNode());
            statement.setLong(5, claim.epoch());
            statement.setTimestamp(6, Timestamp.from(claim.claimedAt()));
            statement.setTimestamp(7, Timestamp.from(claim.claimedAt()));
            statement.setObject(8, claim.claimId());
            statement.setString(9, claim.claimFingerprint());
            statement.executeUpdate();
        }
    }

    private void updateEpoch(Connection connection, AuthorityWriterClaim claim, Instant claimedAt)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE authority_partition_epochs
            SET command_topic = ?,
                owner_node = ?,
                epoch = ?,
                claimed_at = ?,
                updated_at = ?,
                last_claim_id = ?,
                last_claim_fingerprint = ?
            WHERE command_domain = ? AND partition_key = ?
            """)) {
            statement.setString(1, claim.commandTopic());
            statement.setString(2, claim.ownerNode());
            statement.setLong(3, claim.epoch());
            statement.setTimestamp(4, Timestamp.from(claimedAt));
            statement.setTimestamp(5, Timestamp.from(claim.claimedAt()));
            statement.setObject(6, claim.claimId());
            statement.setString(7, claim.claimFingerprint());
            statement.setString(8, claim.commandDomain());
            statement.setString(9, claim.partitionKey());
            statement.executeUpdate();
        }
    }

    private void insertClaim(Connection connection, AuthorityWriterClaim claim) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_writer_claims (
                claim_id, command_domain, command_topic, partition_key, owner_node, epoch,
                previous_owner_node, previous_epoch, claimed_at, claim_fingerprint
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setObject(1, claim.claimId());
            statement.setString(2, claim.commandDomain());
            statement.setString(3, claim.commandTopic());
            statement.setString(4, claim.partitionKey());
            statement.setString(5, claim.ownerNode());
            statement.setLong(6, claim.epoch());
            if (claim.previousOwnerNode() == null) {
                statement.setNull(7, Types.VARCHAR);
            } else {
                statement.setString(7, claim.previousOwnerNode());
            }
            statement.setLong(8, claim.previousEpoch());
            statement.setTimestamp(9, Timestamp.from(claim.claimedAt()));
            statement.setString(10, claim.claimFingerprint());
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Exception exception) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            exception.addSuppressed(rollbackFailure);
        }
    }

    private record ExistingEpoch(String commandTopic, String ownerNode, long epoch, Instant claimedAt) {
    }
}
