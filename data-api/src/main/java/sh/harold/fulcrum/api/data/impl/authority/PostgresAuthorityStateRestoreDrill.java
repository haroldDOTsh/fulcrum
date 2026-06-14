package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Verifies or rebuilds one authority state snapshot from the compacted state changelog.
 */
public final class PostgresAuthorityStateRestoreDrill {
    private static final List<String> REQUIRED_TABLES = List.of(
        "authority_commands",
        "authority_events",
        "authority_state_changelog",
        "authority_state_snapshots",
        "authority_state_restore_runs"
    );
    private static final List<String> REQUIRED_RESTORE_RUN_COLUMNS = List.of(
        "schema_contract_version",
        "schema_contract_fingerprint",
        "restore_source",
        "source_state_fingerprint",
        "snapshot_state_fingerprint",
        "source_event_chain_hash",
        "verification_fingerprint"
    );

    private final PostgresConnectionAdapter connectionAdapter;
    private final FulcrumSchemaContract schemaContract;

    /**
     * Creates a Postgres-backed state restore drill helper.
     *
     * @param connectionAdapter Postgres connection adapter
     */
    public PostgresAuthorityStateRestoreDrill(PostgresConnectionAdapter connectionAdapter) {
        this(connectionAdapter, FulcrumSchemaContract.loadDefault());
    }

    PostgresAuthorityStateRestoreDrill(
        PostgresConnectionAdapter connectionAdapter,
        FulcrumSchemaContract schemaContract
    ) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.schemaContract = Objects.requireNonNull(schemaContract, "schemaContract");
    }

    /**
     * Verifies that restore-drill tables exist.
     */
    public void validateSchema() {
        try (Connection connection = connectionAdapter.getConnection()) {
            validateTables(connection);
            validateRestoreRunColumns(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority state restore schema", exception);
        }
    }

    /**
     * Checks whether the current snapshot matches the latest changelog row for an aggregate.
     *
     * @param aggregateScope aggregate scope, such as {@code rank:player:<uuid>}
     * @param reason operator or test reason
     * @return persisted restore-drill result
     */
    public RestoreRunResult verifyLatestSnapshot(String aggregateScope, String reason) {
        return run(Mode.VERIFY, aggregateScope, reason);
    }

    /**
     * Rebuilds the current snapshot from the latest changelog row for an aggregate.
     *
     * @param aggregateScope aggregate scope, such as {@code rank:player:<uuid>}
     * @param reason operator or test reason
     * @return persisted restore-drill result
     */
    public RestoreRunResult restoreLatestSnapshot(String aggregateScope, String reason) {
        return run(Mode.RESTORE, aggregateScope, reason);
    }

    private RestoreRunResult run(Mode mode, String aggregateScope, String reason) {
        String normalizedScope = normalizedScope(aggregateScope);
        UUID restoreRunId = UUID.randomUUID();
        try (Connection connection = connectionAdapter.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                insertRun(connection, restoreRunId, mode, normalizedScope, reason);
                StateChangelogRow source = loadLatestChangelog(connection, normalizedScope);
                SourceLineageAttestation attestation = source == null
                    ? SourceLineageAttestation.success()
                    : attestSourceLineage(connection, source);
                SnapshotRow snapshot = loadSnapshot(connection, normalizedScope);
                RestoreRunResult result = resultFor(restoreRunId, normalizedScope, source, snapshot, attestation);
                if (mode == Mode.RESTORE && source != null && attestation.verified()) {
                    int restoredRows = restoreSnapshot(connection, source);
                    result = restoredRows > 0
                        ? result.restored("Snapshot restored from authority state changelog")
                        : result.notRestored("Snapshot revision is newer than changelog source");
                }
                completeRun(connection, result);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to run authority state restore drill for " + normalizedScope, exception);
        }
    }

    private RestoreRunResult resultFor(
        UUID restoreRunId,
        String aggregateScope,
        StateChangelogRow source,
        SnapshotRow snapshot,
        SourceLineageAttestation attestation
    ) {
        if (source == null) {
            return new RestoreRunResult(
                restoreRunId,
                aggregateScope,
                Status.SOURCE_MISSING,
                null,
                null,
                null,
                null,
                false,
                "No authority state changelog row exists for aggregate",
                evidence(RestoreSource.NO_CHANGELOG_SOURCE, null, snapshot)
            );
        }
        if (!attestation.verified()) {
            return new RestoreRunResult(
                restoreRunId,
                aggregateScope,
                Status.FAILED,
                source.changelogId(),
                source.eventId(),
                source.revision(),
                snapshot == null ? null : snapshot.revision(),
                false,
                "Authority state changelog source failed ledger attestation: " + attestation.message(),
                evidence(RestoreSource.UNVERIFIABLE_CHANGELOG_LINEAGE, source, snapshot)
            );
        }
        if (snapshot == null) {
            return new RestoreRunResult(
                restoreRunId,
                aggregateScope,
                Status.SNAPSHOT_MISSING,
                source.changelogId(),
                source.eventId(),
                source.revision(),
                null,
                false,
                "Snapshot is missing but changelog source exists",
                evidence(RestoreSource.CHANGELOG_ONLY, source, null)
            );
        }
        if (!snapshot.matches(source)) {
            return new RestoreRunResult(
                restoreRunId,
                aggregateScope,
                Status.MISMATCH_FOUND,
                source.changelogId(),
                source.eventId(),
                source.revision(),
                snapshot.revision(),
                false,
                "Snapshot no longer matches authority state changelog",
                evidence(RestoreSource.DIVERGED_SNAPSHOT, source, snapshot)
            );
        }
        return new RestoreRunResult(
            restoreRunId,
            aggregateScope,
            Status.VERIFIED,
            source.changelogId(),
            source.eventId(),
            source.revision(),
            snapshot.revision(),
            false,
            "Snapshot matches authority state changelog",
            evidence(RestoreSource.CHANGELOG_AND_SNAPSHOT, source, snapshot)
        );
    }

    private void insertRun(
        Connection connection,
        UUID restoreRunId,
        Mode mode,
        String aggregateScope,
        String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_state_restore_runs (
                restore_run_id, aggregate_scope, restore_mode, reason, status, created_at
            ) VALUES (?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)
            """)) {
            statement.setObject(1, restoreRunId);
            statement.setString(2, aggregateScope);
            statement.setString(3, mode.name());
            statement.setString(4, truncate(reason, 2000));
            statement.executeUpdate();
        }
    }

    private SourceLineageAttestation attestSourceLineage(
        Connection connection,
        StateChangelogRow source
    ) throws SQLException {
        List<String> mismatches = new ArrayList<>();
        EventLineageRow event = loadEventLineage(connection, source.eventId());
        if (event == null) {
            mismatches.add("missing authority_events row " + source.eventId());
        } else {
            requireEqual(mismatches, "event.command_id", source.commandId(), event.commandId());
            requireEqual(mismatches, "event.aggregate_scope", source.aggregateScope(), event.aggregateScope());
            requireEqual(mismatches, "event.aggregate_type", source.aggregateType(), event.aggregateType());
            requireEqual(mismatches, "event.aggregate_id", source.aggregateId(), event.aggregateId());
            requireEqual(mismatches, "event.revision", source.revision(), event.revision());
            requireEqual(mismatches, "event.command_domain", source.commandDomain(), event.commandDomain());
            requireEqual(mismatches, "event.partition_key", source.partitionKey(), event.partitionKey());
            requireEqual(mismatches, "event.chain_hash", source.eventChainHash(), event.chainHash());
            requireEqual(mismatches, "event.created_at", source.eventCreatedAt(), event.createdAt());
        }

        CommandLineageRow command = loadCommandLineage(connection, source.commandId());
        if (command == null) {
            mismatches.add("missing authority_commands row " + source.commandId());
        } else {
            if (!command.accepted()) {
                mismatches.add("command.accepted expected true but was false");
            }
            requireEqual(mismatches, "command.result_event_id", source.eventId(), command.resultEventId());
            requireEqual(mismatches, "command.result_revision", source.revision(), command.resultRevision());
            requireEqual(mismatches, "command.command_domain", source.commandDomain(), command.commandDomain());
            requireEqual(mismatches, "command.partition_key", source.partitionKey(), command.partitionKey());
            requireEqual(mismatches, "command.result_state_topic", source.stateTopic(), command.resultStateTopic());
            requireEqual(mismatches, "command.result_partition_key", source.partitionKey(), command.resultPartitionKey());
            requireEqual(mismatches, "command.watermark.source_command_id", source.commandId().toString(),
                command.resultSourceCommandId());
            requireEqual(mismatches, "command.watermark.source_event_id", source.eventId().toString(),
                command.resultSourceEventId());
            requireEqual(mismatches, "command.watermark.source_revision", Long.toString(source.revision()),
                command.resultSourceRevision());
            requireEqual(mismatches, "command.watermark.state_fingerprint", source.stateFingerprint(),
                command.resultStateFingerprint());
            requireEqual(mismatches, "command.watermark.event_chain_hash", source.eventChainHash(),
                command.resultEventChainHash());
            if (!command.resultSettled()) {
                mismatches.add("command.result_payload.settled expected true but was false");
            }
            if (event != null) {
                requireEqual(mismatches, "command.command_type", event.eventType(), command.commandType());
                requireEqual(mismatches, "command.result_event_type", event.eventType(), command.resultEventType());
            }
        }

        return mismatches.isEmpty()
            ? SourceLineageAttestation.success()
            : SourceLineageAttestation.failed(String.join("; ", mismatches));
    }

    private EventLineageRow loadEventLineage(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT command_id, aggregate_scope, aggregate_type, aggregate_id, revision, event_type,
                   command_domain, partition_key, chain_hash, created_at
            FROM authority_events
            WHERE event_id = ?
            """)) {
            statement.setObject(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new EventLineageRow(
                    rows.getObject("command_id", UUID.class),
                    rows.getString("aggregate_scope"),
                    rows.getString("aggregate_type"),
                    rows.getString("aggregate_id"),
                    rows.getLong("revision"),
                    rows.getString("event_type"),
                    rows.getString("command_domain"),
                    rows.getString("partition_key"),
                    rows.getString("chain_hash"),
                    rows.getTimestamp("created_at")
                );
            }
        }
    }

    private CommandLineageRow loadCommandLineage(Connection connection, UUID commandId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT command_id, command_type, accepted, result_event_id, result_event_type, result_revision,
                   command_domain, partition_key,
                   result_payload ->> 'stateTopic' AS result_state_topic,
                   result_payload ->> 'partitionKey' AS result_partition_key,
                   result_payload ->> 'settled' AS result_settled,
                   result_payload #>> '{watermark,sourceCommandId}' AS result_source_command_id,
                   result_payload #>> '{watermark,sourceEventId}' AS result_source_event_id,
                   result_payload #>> '{watermark,sourceRevision}' AS result_source_revision,
                   result_payload #>> '{watermark,stateFingerprint}' AS result_state_fingerprint,
                   result_payload #>> '{watermark,eventChainHash}' AS result_event_chain_hash
            FROM authority_commands
            WHERE command_id = ?
            """)) {
            statement.setObject(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new CommandLineageRow(
                    rows.getObject("command_id", UUID.class),
                    rows.getString("command_type"),
                    rows.getBoolean("accepted"),
                    rows.getObject("result_event_id", UUID.class),
                    rows.getString("result_event_type"),
                    rows.getLong("result_revision"),
                    rows.getString("command_domain"),
                    rows.getString("partition_key"),
                    rows.getString("result_state_topic"),
                    rows.getString("result_partition_key"),
                    rows.getString("result_source_command_id"),
                    rows.getString("result_source_event_id"),
                    rows.getString("result_source_revision"),
                    rows.getString("result_state_fingerprint"),
                    rows.getString("result_event_chain_hash"),
                    Boolean.parseBoolean(rows.getString("result_settled"))
                );
            }
        }
    }

    private StateChangelogRow loadLatestChangelog(Connection connection, String aggregateScope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT changelog_id, event_id, command_id, aggregate_scope, aggregate_type, aggregate_id,
                   revision, command_domain, state_topic, partition_key, state_payload::text AS state_payload,
                   state_fingerprint, event_fingerprint, event_chain_hash, event_created_at
            FROM authority_state_changelog
            WHERE aggregate_scope = ?
            ORDER BY revision DESC, event_created_at DESC
            LIMIT 1
            """)) {
            statement.setString(1, aggregateScope);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new StateChangelogRow(
                    rows.getObject("changelog_id", UUID.class),
                    rows.getObject("event_id", UUID.class),
                    rows.getObject("command_id", UUID.class),
                    rows.getString("aggregate_scope"),
                    rows.getString("aggregate_type"),
                    rows.getString("aggregate_id"),
                    rows.getLong("revision"),
                    rows.getString("command_domain"),
                    rows.getString("state_topic"),
                    rows.getString("partition_key"),
                    rows.getString("state_payload"),
                    rows.getString("state_fingerprint"),
                    rows.getString("event_fingerprint"),
                    rows.getString("event_chain_hash"),
                    rows.getTimestamp("event_created_at")
                );
            }
        }
    }

    private SnapshotRow loadSnapshot(Connection connection, String aggregateScope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT aggregate_scope, aggregate_type, aggregate_id, revision, command_id, event_id,
                   event_created_at, event_fingerprint, event_chain_hash, state_fingerprint,
                   state_payload::text AS state_payload, command_domain, state_topic, partition_key
            FROM authority_state_snapshots
            WHERE aggregate_scope = ?
            """)) {
            statement.setString(1, aggregateScope);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new SnapshotRow(
                    rows.getString("aggregate_scope"),
                    rows.getString("aggregate_type"),
                    rows.getString("aggregate_id"),
                    rows.getLong("revision"),
                    rows.getObject("command_id", UUID.class),
                    rows.getObject("event_id", UUID.class),
                    rows.getTimestamp("event_created_at"),
                    rows.getString("event_fingerprint"),
                    rows.getString("event_chain_hash"),
                    rows.getString("state_fingerprint"),
                    rows.getString("state_payload"),
                    rows.getString("command_domain"),
                    rows.getString("state_topic"),
                    rows.getString("partition_key")
                );
            }
        }
    }

    private int restoreSnapshot(Connection connection, StateChangelogRow source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_state_snapshots (
                aggregate_scope, aggregate_type, aggregate_id, revision, command_id, event_id,
                event_created_at, event_fingerprint, event_chain_hash, state_fingerprint, state_payload,
                command_domain, state_topic, partition_key, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (aggregate_scope) DO UPDATE SET
                aggregate_type = EXCLUDED.aggregate_type,
                aggregate_id = EXCLUDED.aggregate_id,
                revision = EXCLUDED.revision,
                command_id = EXCLUDED.command_id,
                event_id = EXCLUDED.event_id,
                event_created_at = EXCLUDED.event_created_at,
                event_fingerprint = EXCLUDED.event_fingerprint,
                event_chain_hash = EXCLUDED.event_chain_hash,
                state_fingerprint = EXCLUDED.state_fingerprint,
                state_payload = EXCLUDED.state_payload,
                command_domain = EXCLUDED.command_domain,
                state_topic = EXCLUDED.state_topic,
                partition_key = EXCLUDED.partition_key,
                updated_at = CURRENT_TIMESTAMP
            WHERE authority_state_snapshots.revision <= EXCLUDED.revision
            """)) {
            statement.setString(1, source.aggregateScope());
            statement.setString(2, source.aggregateType());
            statement.setString(3, source.aggregateId());
            statement.setLong(4, source.revision());
            statement.setObject(5, source.commandId());
            statement.setObject(6, source.eventId());
            statement.setTimestamp(7, source.eventCreatedAt());
            statement.setString(8, source.eventFingerprint());
            statement.setString(9, source.eventChainHash());
            statement.setString(10, source.stateFingerprint());
            statement.setString(11, source.statePayload());
            statement.setString(12, source.commandDomain());
            statement.setString(13, source.stateTopic());
            statement.setString(14, source.partitionKey());
            return statement.executeUpdate();
        }
    }

    private void completeRun(Connection connection, RestoreRunResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE authority_state_restore_runs
            SET status = ?,
                source_changelog_id = ?,
                source_event_id = ?,
                source_revision = ?,
                snapshot_revision = ?,
                restored = ?,
                message = ?,
                schema_contract_version = ?,
                schema_contract_fingerprint = ?,
                restore_source = ?,
                source_state_fingerprint = ?,
                snapshot_state_fingerprint = ?,
                source_event_chain_hash = ?,
                verification_fingerprint = ?,
                completed_at = CURRENT_TIMESTAMP
            WHERE restore_run_id = ?
            """)) {
            statement.setString(1, result.status().name());
            statement.setObject(2, result.sourceChangelogId());
            statement.setObject(3, result.sourceEventId());
            if (result.sourceRevision() == null) {
                statement.setObject(4, null);
            } else {
                statement.setLong(4, result.sourceRevision());
            }
            if (result.snapshotRevision() == null) {
                statement.setObject(5, null);
            } else {
                statement.setLong(5, result.snapshotRevision());
            }
            statement.setBoolean(6, result.restored());
            statement.setString(7, result.message());
            RestoreEvidence evidence = result.evidence();
            statement.setInt(8, evidence.schemaContractVersion());
            statement.setString(9, evidence.schemaContractFingerprint());
            statement.setString(10, evidence.restoreSource());
            statement.setString(11, evidence.sourceStateFingerprint());
            statement.setString(12, evidence.snapshotStateFingerprint());
            statement.setString(13, evidence.sourceEventChainHash());
            statement.setString(14, result.verificationFingerprint());
            statement.setObject(15, result.restoreRunId());
            statement.executeUpdate();
        }
    }

    private void validateTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : REQUIRED_TABLES) {
                statement.setString(1, table);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new IllegalStateException(
                            "Missing required authority state restore table '" + table
                                + "'. Run data-api migrations before verifying state restore."
                        );
                    }
                }
            }
        }
    }

    private void validateRestoreRunColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'authority_state_restore_runs'
              AND column_name = ?
            """)) {
            for (String column : REQUIRED_RESTORE_RUN_COLUMNS) {
                statement.setString(1, column);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException(
                            "Missing authority state restore evidence column '"
                                + column + "'. Run data-api migrations before verifying state restore."
                        );
                    }
                }
            }
        }
    }

    private RestoreEvidence evidence(
        RestoreSource restoreSource,
        StateChangelogRow source,
        SnapshotRow snapshot
    ) {
        return new RestoreEvidence(
            schemaContract.version(),
            schemaContract.fingerprint(),
            restoreSource.name(),
            source == null ? null : source.stateFingerprint(),
            snapshot == null ? null : snapshot.stateFingerprint(),
            source == null ? null : source.eventChainHash()
        );
    }

    private static void requireEqual(List<String> mismatches, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            mismatches.add(field + " expected " + string(expected) + " but was " + string(actual));
        }
    }

    private static String normalizedScope(String aggregateScope) {
        if (aggregateScope == null || aggregateScope.isBlank()) {
            throw new IllegalArgumentException("aggregateScope must not be blank");
        }
        return aggregateScope;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private enum Mode {
        VERIFY,
        RESTORE
    }

    private enum RestoreSource {
        NO_CHANGELOG_SOURCE,
        CHANGELOG_ONLY,
        CHANGELOG_AND_SNAPSHOT,
        DIVERGED_SNAPSHOT,
        UNVERIFIABLE_CHANGELOG_LINEAGE,
        CHANGELOG_RESTORED,
        RESTORE_SKIPPED_NEWER_SNAPSHOT
    }

    /**
     * Restore drill outcome.
     */
    public enum Status {
        SOURCE_MISSING,
        SNAPSHOT_MISSING,
        MISMATCH_FOUND,
        VERIFIED,
        RESTORED,
        FAILED
    }

    /**
     * Persisted restore drill result.
     */
    public record RestoreRunResult(
        UUID restoreRunId,
        String aggregateScope,
        Status status,
        UUID sourceChangelogId,
        UUID sourceEventId,
        Long sourceRevision,
        Long snapshotRevision,
        boolean restored,
        String message,
        RestoreEvidence evidence
    ) {
        public boolean clean() {
            return status == Status.VERIFIED || status == Status.RESTORED;
        }

        public String verificationFingerprint() {
            return sha256(String.join("|",
                restoreRunId.toString(),
                aggregateScope,
                status.name(),
                string(sourceChangelogId),
                string(sourceEventId),
                string(sourceRevision),
                string(snapshotRevision),
                Boolean.toString(restored),
                message == null ? "" : message,
                Integer.toString(evidence.schemaContractVersion()),
                evidence.schemaContractFingerprint(),
                evidence.restoreSource(),
                string(evidence.sourceStateFingerprint()),
                string(evidence.snapshotStateFingerprint()),
                string(evidence.sourceEventChainHash())
            ));
        }

        RestoreRunResult restored(String message) {
            return new RestoreRunResult(
                restoreRunId,
                aggregateScope,
                Status.RESTORED,
                sourceChangelogId,
                sourceEventId,
                sourceRevision,
                sourceRevision,
                true,
                message,
                evidence.restoredFromChangelog()
            );
        }

        private RestoreRunResult notRestored(String message) {
            return new RestoreRunResult(
                restoreRunId,
                aggregateScope,
                Status.MISMATCH_FOUND,
                sourceChangelogId,
                sourceEventId,
                sourceRevision,
                snapshotRevision,
                false,
                message,
                evidence.skippedNewerSnapshot()
            );
        }
    }

    /**
     * Restore evidence persisted with each drill result.
     */
    public record RestoreEvidence(
        int schemaContractVersion,
        String schemaContractFingerprint,
        String restoreSource,
        String sourceStateFingerprint,
        String snapshotStateFingerprint,
        String sourceEventChainHash
    ) {
        private RestoreEvidence restoredFromChangelog() {
            if (sourceStateFingerprint == null) {
                return this;
            }
            return new RestoreEvidence(
                schemaContractVersion,
                schemaContractFingerprint,
                RestoreSource.CHANGELOG_RESTORED.name(),
                sourceStateFingerprint,
                sourceStateFingerprint,
                sourceEventChainHash
            );
        }

        private RestoreEvidence skippedNewerSnapshot() {
            return new RestoreEvidence(
                schemaContractVersion,
                schemaContractFingerprint,
                RestoreSource.RESTORE_SKIPPED_NEWER_SNAPSHOT.name(),
                sourceStateFingerprint,
                snapshotStateFingerprint,
                sourceEventChainHash
            );
        }
    }

    private record SourceLineageAttestation(boolean verified, String message) {
        private static SourceLineageAttestation success() {
            return new SourceLineageAttestation(true, "verified");
        }

        private static SourceLineageAttestation failed(String message) {
            return new SourceLineageAttestation(false, message);
        }
    }

    private record EventLineageRow(
        UUID commandId,
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        long revision,
        String eventType,
        String commandDomain,
        String partitionKey,
        String chainHash,
        Timestamp createdAt
    ) {
    }

    private record CommandLineageRow(
        UUID commandId,
        String commandType,
        boolean accepted,
        UUID resultEventId,
        String resultEventType,
        long resultRevision,
        String commandDomain,
        String partitionKey,
        String resultStateTopic,
        String resultPartitionKey,
        String resultSourceCommandId,
        String resultSourceEventId,
        String resultSourceRevision,
        String resultStateFingerprint,
        String resultEventChainHash,
        boolean resultSettled
    ) {
    }

    private record StateChangelogRow(
        UUID changelogId,
        UUID eventId,
        UUID commandId,
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        long revision,
        String commandDomain,
        String stateTopic,
        String partitionKey,
        String statePayload,
        String stateFingerprint,
        String eventFingerprint,
        String eventChainHash,
        Timestamp eventCreatedAt
    ) {
    }

    private record SnapshotRow(
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        long revision,
        UUID commandId,
        UUID eventId,
        Timestamp eventCreatedAt,
        String eventFingerprint,
        String eventChainHash,
        String stateFingerprint,
        String statePayload,
        String commandDomain,
        String stateTopic,
        String partitionKey
    ) {
        boolean matches(StateChangelogRow source) {
            return revision == source.revision()
                && Objects.equals(commandId, source.commandId())
                && Objects.equals(eventId, source.eventId())
                && Objects.equals(aggregateScope, source.aggregateScope())
                && Objects.equals(aggregateType, source.aggregateType())
                && Objects.equals(aggregateId, source.aggregateId())
                && Objects.equals(eventCreatedAt, source.eventCreatedAt())
                && Objects.equals(eventFingerprint, source.eventFingerprint())
                && Objects.equals(eventChainHash, source.eventChainHash())
                && Objects.equals(stateFingerprint, source.stateFingerprint())
                && Objects.equals(statePayload, source.statePayload())
                && Objects.equals(commandDomain, source.commandDomain())
                && Objects.equals(stateTopic, source.stateTopic())
                && Objects.equals(partitionKey, source.partitionKey());
        }
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority restore evidence", exception);
        }
    }
}
