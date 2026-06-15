package sh.harold.fulcrum.api.data.impl.authority.events;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pulls immutable authority events from Postgres and tracks delivery progress per consumer.
 */
public final class PostgresAuthorityEventDispatcher {
    private static final List<String> REQUIRED_TABLES = List.of(
        "authority_events",
        "authority_event_consumer_cursors",
        "authority_event_consumer_failures",
        "authority_projection_checkpoints",
        "authority_projection_heads",
        "authority_projection_manifests"
    );

    private final PostgresConnectionAdapter connectionAdapter;
    private final List<AuthorityEventDispatchTarget> targets;
    private final Options options;
    private final Gson gson = new Gson();

    /**
     * Creates a dispatcher with default batch and retry settings.
     *
     * @param connectionAdapter Postgres connection adapter
     * @param targets dispatch consumers
     */
    public PostgresAuthorityEventDispatcher(
        PostgresConnectionAdapter connectionAdapter,
        Collection<AuthorityEventDispatchTarget> targets
    ) {
        this(connectionAdapter, targets, Options.defaults());
    }

    /**
     * Creates a dispatcher with explicit batch and retry settings.
     *
     * @param connectionAdapter Postgres connection adapter
     * @param targets dispatch consumers
     * @param options dispatcher options
     */
    public PostgresAuthorityEventDispatcher(
        PostgresConnectionAdapter connectionAdapter,
        Collection<AuthorityEventDispatchTarget> targets,
        Options options
    ) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.options = options == null ? Options.defaults() : options.normalized();
        validateTargets(this.targets);
    }

    /**
     * Verifies that the authority event dispatcher tables exist.
     */
    public void validateSchema() {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : REQUIRED_TABLES) {
                statement.setString(1, table);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new IllegalStateException(
                            "Missing required authority event dispatcher table '" + table
                                + "'. Run data-api migrations before enabling authority event dispatch."
                        );
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority event dispatcher schema", exception);
        }
    }

    /**
     * Runs one bounded dispatch cycle for every configured consumer.
     *
     * @return dispatch cycle summary
     */
    public DispatchCycleResult dispatchOnce() {
        int attempted = 0;
        int delivered = 0;
        int skipped = 0;
        int retries = 0;
        int quarantined = 0;
        int blocked = 0;

        for (AuthorityEventDispatchTarget target : targets) {
            TargetCycleResult targetResult = dispatchTarget(target);
            attempted += targetResult.attempted();
            delivered += targetResult.delivered();
            skipped += targetResult.skipped();
            retries += targetResult.retries();
            quarantined += targetResult.quarantined();
            blocked += targetResult.blocked();
        }

        return new DispatchCycleResult(targets.size(), attempted, delivered, skipped, retries, quarantined, blocked);
    }

    private TargetCycleResult dispatchTarget(AuthorityEventDispatchTarget target) {
        try (Connection connection = connectionAdapter.getConnection()) {
            String consumerName = normalizedConsumerName(target);
            AuthorityProjectionManifest manifest = normalizedProjectionManifest(target, consumerName);
            ConsumerCursor cursor = loadCursor(connection, consumerName);
            List<AuthorityEventEnvelope> events = loadEvents(connection, cursor, options.batchSize());
            int attempted = 0;
            int delivered = 0;
            int skipped = 0;
            int retries = 0;
            int quarantined = 0;
            int blocked = 0;

            for (AuthorityEventEnvelope event : events) {
                FailureState failure = loadFailure(connection, consumerName, event.eventId());
                if (failure != null && failure.quarantined()) {
                    blocked++;
                    break;
                }
                if (failure != null && failure.nextAttemptAt().isAfter(Instant.now())) {
                    blocked++;
                    break;
                }

                attempted++;
                if (!manifest.acceptsEventType(event.eventType())) {
                    recordSkippedDispatch(connection, consumerName, manifest, event);
                    skipped++;
                    continue;
                }
                AuthorityEventSequenceGuard.SequenceVerdict sequence =
                    AuthorityEventSequenceGuard.verify(
                        consumerName,
                        event,
                        loadLastCheckpointRevision(connection, consumerName, event.aggregateScope())
                    );
                if (!sequence.accepted()) {
                    recordFailure(
                        connection,
                        consumerName,
                        event.eventId(),
                        AuthorityEventDispatchResult.quarantine(sequence.message())
                    );
                    quarantined++;
                    break;
                }
                AuthorityEventDispatchResult result = dispatch(target, event);
                if (result.successful()) {
                    recordSuccessfulDispatch(connection, consumerName, manifest, event, result);
                    delivered++;
                    continue;
                }
                recordFailure(connection, consumerName, event.eventId(), result);
                if (result.status() == AuthorityEventDispatchResult.Status.QUARANTINED) {
                    quarantined++;
                } else {
                    retries++;
                }
                break;
            }

            return new TargetCycleResult(attempted, delivered, skipped, retries, quarantined, blocked);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to dispatch authority events", exception);
        }
    }

    private AuthorityEventDispatchResult dispatch(
        AuthorityEventDispatchTarget target,
        AuthorityEventEnvelope event
    ) {
        try {
            AuthorityEventDispatchResult result = target.dispatch(event);
            return result == null
                ? AuthorityEventDispatchResult.retry("Dispatch target returned no result")
                : result;
        } catch (Exception exception) {
            return AuthorityEventDispatchResult.retry(exception.getMessage());
        }
    }

    private Long loadLastCheckpointRevision(
        Connection connection,
        String consumerName,
        String aggregateScope
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT revision
            FROM authority_projection_checkpoints
            WHERE projection_name = ? AND aggregate_scope = ?
            ORDER BY revision DESC
            LIMIT 1
            """)) {
            statement.setString(1, consumerName);
            statement.setString(2, aggregateScope);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return rows.getLong("revision");
            }
        }
    }

    private ConsumerCursor loadCursor(Connection connection, String consumerName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT last_event_id, last_event_created_at
            FROM authority_event_consumer_cursors
            WHERE consumer_name = ?
            """)) {
            statement.setString(1, consumerName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new ConsumerCursor(
                    rows.getObject("last_event_id", UUID.class),
                    rows.getTimestamp("last_event_created_at").toInstant()
                );
            }
        }
    }

    private List<AuthorityEventEnvelope> loadEvents(
        Connection connection,
        ConsumerCursor cursor,
        int limit
    ) throws SQLException {
        String sql = cursor == null
            ? """
                SELECT event_id, command_id, aggregate_scope, aggregate_type, aggregate_id,
                       revision, event_type, payload::text AS payload,
                       provenance::text AS provenance, chain_hash, created_at
                FROM authority_events
                ORDER BY created_at, event_id
                LIMIT ?
                """
            : """
                SELECT event_id, command_id, aggregate_scope, aggregate_type, aggregate_id,
                       revision, event_type, payload::text AS payload,
                       provenance::text AS provenance, chain_hash, created_at
                FROM authority_events
                WHERE created_at > ?
                   OR (created_at = ? AND event_id > ?)
                ORDER BY created_at, event_id
                LIMIT ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (cursor == null) {
                statement.setInt(1, limit);
            } else {
                Timestamp lastCreatedAt = Timestamp.from(cursor.lastEventCreatedAt());
                statement.setTimestamp(1, lastCreatedAt);
                statement.setTimestamp(2, lastCreatedAt);
                statement.setObject(3, cursor.lastEventId());
                statement.setInt(4, limit);
            }

            List<AuthorityEventEnvelope> events = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(readEvent(rows));
                }
            }
            return events;
        }
    }

    private AuthorityEventEnvelope readEvent(ResultSet rows) throws SQLException {
        return new AuthorityEventEnvelope(
            rows.getObject("event_id", UUID.class),
            rows.getObject("command_id", UUID.class),
            rows.getString("aggregate_scope"),
            rows.getString("aggregate_type"),
            rows.getString("aggregate_id"),
            rows.getLong("revision"),
            rows.getString("event_type"),
            jsonMap(rows.getString("payload")),
            jsonMap(rows.getString("provenance")),
            rows.getString("chain_hash"),
            rows.getTimestamp("created_at").toInstant()
        );
    }

    private void recordSuccessfulDispatch(
        Connection connection,
        String consumerName,
        AuthorityProjectionManifest manifest,
        AuthorityEventEnvelope event,
        AuthorityEventDispatchResult result
    ) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            String inputFingerprint = AuthorityEventFingerprints.inputFingerprint(event);
            String outputFingerprint = AuthorityEventFingerprints.outputFingerprint(result, inputFingerprint);
            recordProjectionManifest(connection, manifest);
            recordProjectionCheckpoint(
                connection,
                consumerName,
                manifest,
                event,
                result,
                inputFingerprint,
                outputFingerprint
            );
            recordProjectionHead(
                connection,
                consumerName,
                manifest,
                event,
                result,
                inputFingerprint,
                outputFingerprint
            );
            advanceCursor(connection, consumerName, event);
            deleteFailure(connection, consumerName, event.eventId());
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void recordSkippedDispatch(
        Connection connection,
        String consumerName,
        AuthorityProjectionManifest manifest,
        AuthorityEventEnvelope event
    ) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            recordProjectionManifest(connection, manifest);
            advanceCursor(connection, consumerName, event);
            deleteFailure(connection, consumerName, event.eventId());
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void recordProjectionCheckpoint(
        Connection connection,
        String consumerName,
        AuthorityProjectionManifest manifest,
        AuthorityEventEnvelope event,
        AuthorityEventDispatchResult result,
        String inputFingerprint,
        String outputFingerprint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_projection_checkpoints (
                projection_name, event_id, event_created_at, aggregate_scope, revision, event_type,
                projection_version, input_fingerprint, output_fingerprint, manifest_fingerprint,
                manifest_payload, replay_batch_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (projection_name, event_id) DO NOTHING
            """)) {
            statement.setString(1, consumerName);
            statement.setObject(2, event.eventId());
            statement.setTimestamp(3, Timestamp.from(event.createdAt()));
            statement.setString(4, event.aggregateScope());
            statement.setLong(5, event.revision());
            statement.setString(6, event.eventType());
            statement.setString(7, result.projectionVersion());
            statement.setString(8, inputFingerprint);
            statement.setString(9, outputFingerprint);
            statement.setString(10, manifest.manifestFingerprint());
            statement.setString(11, manifest.manifestPayload());
            statement.setObject(12, result.replayBatchId());
            statement.executeUpdate();
        }
    }

    private void recordProjectionManifest(
        Connection connection,
        AuthorityProjectionManifest manifest
    ) throws SQLException {
        java.sql.Array acceptedEventTypes = connection.createArrayOf(
            "text",
            manifest.acceptedEventTypes().toArray(String[]::new)
        );
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_projection_manifests (
                projection_name, projection_version, manifest_fingerprint,
                accepted_event_types, manifest_payload, updated_at
            ) VALUES (?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
            ON CONFLICT (projection_name) DO UPDATE SET
                projection_version = EXCLUDED.projection_version,
                manifest_fingerprint = EXCLUDED.manifest_fingerprint,
                accepted_event_types = EXCLUDED.accepted_event_types,
                manifest_payload = EXCLUDED.manifest_payload,
                updated_at = CURRENT_TIMESTAMP
            """)) {
            statement.setString(1, manifest.projectionName());
            statement.setString(2, manifest.projectionVersion());
            statement.setString(3, manifest.manifestFingerprint());
            statement.setArray(4, acceptedEventTypes);
            statement.setString(5, manifest.manifestPayload());
            statement.executeUpdate();
        } finally {
            acceptedEventTypes.free();
        }
    }

    private void recordProjectionHead(
        Connection connection,
        String consumerName,
        AuthorityProjectionManifest manifest,
        AuthorityEventEnvelope event,
        AuthorityEventDispatchResult result,
        String inputFingerprint,
        String outputFingerprint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_projection_heads (
                projection_name, event_id, event_created_at, aggregate_scope, revision, event_type,
                projection_version, input_fingerprint, output_fingerprint, manifest_fingerprint,
                manifest_payload, replay_batch_id, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (projection_name) DO UPDATE SET
                event_id = EXCLUDED.event_id,
                event_created_at = EXCLUDED.event_created_at,
                aggregate_scope = EXCLUDED.aggregate_scope,
                revision = EXCLUDED.revision,
                event_type = EXCLUDED.event_type,
                projection_version = EXCLUDED.projection_version,
                input_fingerprint = EXCLUDED.input_fingerprint,
                output_fingerprint = EXCLUDED.output_fingerprint,
                manifest_fingerprint = EXCLUDED.manifest_fingerprint,
                manifest_payload = EXCLUDED.manifest_payload,
                replay_batch_id = EXCLUDED.replay_batch_id,
                updated_at = CURRENT_TIMESTAMP
            """)) {
            statement.setString(1, consumerName);
            statement.setObject(2, event.eventId());
            statement.setTimestamp(3, Timestamp.from(event.createdAt()));
            statement.setString(4, event.aggregateScope());
            statement.setLong(5, event.revision());
            statement.setString(6, event.eventType());
            statement.setString(7, result.projectionVersion());
            statement.setString(8, inputFingerprint);
            statement.setString(9, outputFingerprint);
            statement.setString(10, manifest.manifestFingerprint());
            statement.setString(11, manifest.manifestPayload());
            statement.setObject(12, result.replayBatchId());
            statement.executeUpdate();
        }
    }

    private FailureState loadFailure(Connection connection, String consumerName, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT failure_status, next_attempt_at
            FROM authority_event_consumer_failures
            WHERE consumer_name = ? AND event_id = ?
            """)) {
            statement.setString(1, consumerName);
            statement.setObject(2, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new FailureState(
                    "QUARANTINED".equals(rows.getString("failure_status")),
                    rows.getTimestamp("next_attempt_at").toInstant()
                );
            }
        }
    }

    private void advanceCursor(
        Connection connection,
        String consumerName,
        AuthorityEventEnvelope event
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_event_consumer_cursors (
                consumer_name, last_event_id, last_event_created_at, last_aggregate_scope, last_revision, updated_at
            ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (consumer_name) DO UPDATE SET
                last_event_id = EXCLUDED.last_event_id,
                last_event_created_at = EXCLUDED.last_event_created_at,
                last_aggregate_scope = EXCLUDED.last_aggregate_scope,
                last_revision = EXCLUDED.last_revision,
                updated_at = CURRENT_TIMESTAMP
            """)) {
            statement.setString(1, consumerName);
            statement.setObject(2, event.eventId());
            statement.setTimestamp(3, Timestamp.from(event.createdAt()));
            statement.setString(4, event.aggregateScope());
            statement.setLong(5, event.revision());
            statement.executeUpdate();
        }
    }

    private void deleteFailure(Connection connection, String consumerName, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM authority_event_consumer_failures
            WHERE consumer_name = ? AND event_id = ?
            """)) {
            statement.setString(1, consumerName);
            statement.setObject(2, eventId);
            statement.executeUpdate();
        }
    }

    private void recordFailure(
        Connection connection,
        String consumerName,
        UUID eventId,
        AuthorityEventDispatchResult result
    ) throws SQLException {
        String status = result.status() == AuthorityEventDispatchResult.Status.QUARANTINED
            ? "QUARANTINED"
            : "RETRY";
        Instant nextAttemptAt = "RETRY".equals(status)
            ? Instant.now().plus(options.retryDelay())
            : Instant.now();
        String message = truncate(result.message(), 2000);
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_event_consumer_failures (
                consumer_name, event_id, failure_status, failure_message, failure_fingerprint,
                attempts, next_attempt_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (consumer_name, event_id) DO UPDATE SET
                failure_status = EXCLUDED.failure_status,
                failure_message = EXCLUDED.failure_message,
                failure_fingerprint = EXCLUDED.failure_fingerprint,
                attempts = authority_event_consumer_failures.attempts + 1,
                next_attempt_at = EXCLUDED.next_attempt_at,
                updated_at = CURRENT_TIMESTAMP
            """)) {
            statement.setString(1, consumerName);
            statement.setObject(2, eventId);
            statement.setString(3, status);
            statement.setString(4, message);
            statement.setString(5, failureFingerprint(status, message));
            statement.setTimestamp(6, Timestamp.from(nextAttemptAt));
            statement.executeUpdate();
        }
    }

    private Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<?, ?> parsed = gson.fromJson(json, Map.class);
        if (parsed == null || parsed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new HashMap<>();
        parsed.forEach((key, value) -> {
            if (key != null) {
                values.put(key.toString(), value);
            }
        });
        return values;
    }

    private static void validateTargets(List<AuthorityEventDispatchTarget> targets) {
        for (AuthorityEventDispatchTarget target : targets) {
            String consumerName = normalizedConsumerName(target);
            normalizedProjectionManifest(target, consumerName);
        }
    }

    private static String normalizedConsumerName(AuthorityEventDispatchTarget target) {
        Objects.requireNonNull(target, "target");
        String consumerName = target.consumerName();
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("Authority event dispatch consumerName is required");
        }
        return consumerName.trim();
    }

    private static AuthorityProjectionManifest normalizedProjectionManifest(
        AuthorityEventDispatchTarget target,
        String consumerName
    ) {
        AuthorityProjectionManifest manifest = target.projectionManifest();
        if (manifest == null) {
            manifest = AuthorityProjectionManifest.unversioned(consumerName);
        }
        if (!consumerName.equals(manifest.projectionName())) {
            throw new IllegalArgumentException(
                "Projection manifest name '" + manifest.projectionName()
                    + "' must match dispatch consumerName '" + consumerName + "'"
            );
        }
        return manifest;
    }

    private static String failureFingerprint(String status, String message) {
        return AuthorityEventFingerprints.sha256(status + ":" + message);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * Dispatcher tuning values.
     *
     * @param batchSize maximum events considered per consumer per cycle
     * @param retryDelay delay before retrying a retryable consumer failure
     */
    public record Options(int batchSize, Duration retryDelay) {
        /**
         * Returns conservative defaults for registry-owned dispatch.
         *
         * @return default dispatcher options
         */
        public static Options defaults() {
            return new Options(50, Duration.ofSeconds(5));
        }

        private Options normalized() {
            int normalizedBatchSize = batchSize > 0 ? batchSize : defaults().batchSize();
            Duration normalizedRetryDelay = retryDelay == null || retryDelay.isNegative()
                ? defaults().retryDelay()
                : retryDelay;
            return new Options(normalizedBatchSize, normalizedRetryDelay);
        }
    }

    /**
     * Summary of one dispatcher cycle across all configured consumers.
     *
     * @param targetCount number of consumers checked
     * @param attempted events passed to consumers
     * @param delivered events successfully delivered
     * @param skipped events intentionally skipped by projection manifests
     * @param retries retryable failures recorded
     * @param quarantined quarantined failures recorded
     * @param blocked consumers blocked by an existing failure row
     */
    public record DispatchCycleResult(
        int targetCount,
        int attempted,
        int delivered,
        int skipped,
        int retries,
        int quarantined,
        int blocked
    ) {
        /**
         * Indicates whether the cycle observed or changed any dispatch state.
         *
         * @return true when the cycle did useful work or found a blocked row
         */
        public boolean hasWork() {
            return attempted > 0 || delivered > 0 || skipped > 0 || retries > 0 || quarantined > 0 || blocked > 0;
        }
    }

    private record TargetCycleResult(int attempted, int delivered, int skipped, int retries, int quarantined, int blocked) {
    }

    private record ConsumerCursor(UUID lastEventId, Instant lastEventCreatedAt) {
    }

    private record FailureState(boolean quarantined, Instant nextAttemptAt) {
    }
}
