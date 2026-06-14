package sh.harold.fulcrum.api.data.impl.authority.events;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Records bounded dry-run replay attestations for authority projection checkpoints.
 */
public final class PostgresAuthorityProjectionReplayVerifier {
    private static final int DEFAULT_LIMIT = 100;
    private static final List<String> REQUIRED_TABLES = List.of(
        "authority_events",
        "authority_projection_checkpoints",
        "authority_projection_manifests",
        "authority_projection_replay_runs",
        "authority_projection_replay_run_events"
    );
    private static final List<String> REQUIRED_REPLAY_RUN_COLUMNS = List.of(
        "skipped_events",
        "schema_contract_version",
        "schema_contract_fingerprint",
        "projection_manifest_fingerprint",
        "replay_source",
        "event_range_fingerprint",
        "verification_fingerprint"
    );
    private static final String REPLAY_SOURCE = "AUTHORITY_EVENT_LOG";

    private final PostgresConnectionAdapter connectionAdapter;
    private final FulcrumSchemaContract schemaContract;
    private final Gson gson = new Gson();

    /**
     * Creates a replay verifier backed by the authority Postgres database.
     *
     * @param connectionAdapter Postgres connection adapter
     */
    public PostgresAuthorityProjectionReplayVerifier(PostgresConnectionAdapter connectionAdapter) {
        this(connectionAdapter, FulcrumSchemaContract.loadDefault());
    }

    PostgresAuthorityProjectionReplayVerifier(
        PostgresConnectionAdapter connectionAdapter,
        FulcrumSchemaContract schemaContract
    ) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.schemaContract = Objects.requireNonNull(schemaContract, "schemaContract");
    }

    /**
     * Verifies that the authority replay attestation tables exist.
     */
    public void validateSchema() {
        try (Connection connection = connectionAdapter.getConnection()) {
            validateTables(connection);
            validateReplayRunColumns(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority projection replay schema", exception);
        }
    }

    /**
     * Verifies that checkpoint input fingerprints still match the immutable authority event log.
     *
     * @param projectionName projection checkpoint name
     * @param window bounded event window
     * @param reason operator or test reason for the verification run
     * @return persisted replay verification summary
     */
    public ReplayVerificationResult verifyCheckpoints(
        String projectionName,
        ReplayWindow window,
        String reason
    ) {
        return verify(normalizedProjectionName(projectionName), null, window, reason);
    }

    /**
     * Verifies checkpoint input fingerprints and side-effect-free replay output against checkpoint receipts.
     *
     * @param target deterministic dry-run projection target
     * @param window bounded event window
     * @param reason operator or test reason for the verification run
     * @return persisted replay verification summary
     */
    public ReplayVerificationResult verifyReplayTarget(
        AuthorityEventReplayTarget target,
        ReplayWindow window,
        String reason
    ) {
        Objects.requireNonNull(target, "target");
        return verify(normalizedProjectionName(target.projectionName()), target, window, reason);
    }

    private ReplayVerificationResult verify(
        String projectionName,
        AuthorityEventReplayTarget target,
        ReplayWindow window,
        String reason
    ) {
        ReplayWindow normalizedWindow = normalizedWindow(window);
        AuthorityProjectionManifest manifest = target == null ? null : normalizedReplayManifest(target, projectionName);
        UUID replayRunId = UUID.randomUUID();

        try (Connection connection = connectionAdapter.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                insertRun(connection, replayRunId, projectionName, normalizedWindow.limit(), reason);
                List<AuthorityEventEnvelope> events = loadEvents(connection, normalizedWindow);
                RunCounters counters = new RunCounters();
                AuthorityEventEnvelope start = null;
                AuthorityEventEnvelope end = null;
                List<VerificationEvent> verifications = new ArrayList<>();

                for (AuthorityEventEnvelope event : events) {
                    if (start == null) {
                        start = event;
                    }
                    end = event;
                    VerificationEvent verification = verifyEvent(connection, projectionName, target, manifest, event);
                    insertRunEvent(connection, replayRunId, verification);
                    verifications.add(verification);
                    counters.record(verification.verdict());
                }

                Status status = counters.status();
                ReplayEvidence evidence = evidence(projectionName, status, counters, manifest, verifications);
                updateRun(connection, replayRunId, status, counters, start, end, evidence);
                connection.commit();
                return new ReplayVerificationResult(
                    replayRunId,
                    projectionName,
                    status,
                    counters.scannedEvents(),
                    counters.verifiedEvents(),
                    counters.skippedEvents(),
                    counters.missingCheckpoints(),
                    counters.inputMismatches(),
                    counters.outputMismatches(),
                    counters.manifestMismatches(),
                    counters.replayFailures(),
                    evidence.schemaContractVersion(),
                    evidence.schemaContractFingerprint(),
                    evidence.projectionManifestFingerprint(),
                    evidence.replaySource(),
                    evidence.eventRangeFingerprint(),
                    evidence.verificationFingerprint()
                );
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to verify authority projection replay", exception);
        }
    }

    private VerificationEvent verifyEvent(
        Connection connection,
        String projectionName,
        AuthorityEventReplayTarget target,
        AuthorityProjectionManifest manifest,
        AuthorityEventEnvelope event
    ) throws SQLException {
        Checkpoint checkpoint = loadCheckpoint(connection, projectionName, event.eventId());
        String actualInputFingerprint = AuthorityEventFingerprints.inputFingerprint(event);

        if (target != null && manifest != null && !manifest.acceptsEventType(event.eventType())) {
            if (checkpoint == null) {
                return VerificationEvent.skippedByManifest(event, actualInputFingerprint, manifest);
            }
            return VerificationEvent.manifestMismatch(
                event,
                checkpoint,
                actualInputFingerprint,
                manifest,
                "Projection manifest excludes event type " + event.eventType()
                    + " but checkpoint receipt exists"
            );
        }
        if (checkpoint == null) {
            return VerificationEvent.missingCheckpoint(event, actualInputFingerprint);
        }
        if (!checkpoint.inputFingerprint().equals(actualInputFingerprint)) {
            return VerificationEvent.inputMismatch(event, checkpoint, actualInputFingerprint);
        }
        if (target == null) {
            return VerificationEvent.verified(event, checkpoint, actualInputFingerprint, null, null);
        }
        VerificationEvent manifestMismatch = verifyManifest(event, checkpoint, manifest, actualInputFingerprint);
        if (manifestMismatch != null) {
            return manifestMismatch;
        }

        try {
            AuthorityEventReplayResult result = target.replay(event);
            if (result == null) {
                return VerificationEvent.replayFailed(
                    event,
                    checkpoint,
                    actualInputFingerprint,
                    "Replay target returned no result"
                );
            }
            String message = null;
            if (!checkpoint.projectionVersion().equals(result.projectionVersion())) {
                message = "Projection version drift: expected " + checkpoint.projectionVersion()
                    + " but replay produced " + result.projectionVersion();
            } else if (!checkpoint.outputFingerprint().equals(result.outputFingerprint())) {
                message = "Output fingerprint drift";
            }
            if (message != null) {
                return VerificationEvent.outputMismatch(
                    event,
                    checkpoint,
                    actualInputFingerprint,
                    result.outputFingerprint(),
                    message
                );
            }
            return VerificationEvent.verified(
                event,
                checkpoint,
                actualInputFingerprint,
                result.outputFingerprint(),
                manifest
            );
        } catch (Exception exception) {
            return VerificationEvent.replayFailed(
                event,
                checkpoint,
                actualInputFingerprint,
                truncate(exception.getMessage(), 2000)
            );
        }
    }

    private VerificationEvent verifyManifest(
        AuthorityEventEnvelope event,
        Checkpoint checkpoint,
        AuthorityProjectionManifest manifest,
        String actualInputFingerprint
    ) {
        if (!manifest.acceptsEventType(event.eventType())) {
            return VerificationEvent.manifestMismatch(
                event,
                checkpoint,
                actualInputFingerprint,
                manifest,
                "Projection manifest does not accept event type " + event.eventType()
            );
        }
        if (checkpoint.manifestFingerprint() == null || checkpoint.manifestFingerprint().isBlank()) {
            return manifest.acceptsAllEventTypes()
                ? null
                : VerificationEvent.manifestMismatch(
                    event,
                    checkpoint,
                    actualInputFingerprint,
                    manifest,
                    "Checkpoint receipt has no projection manifest fingerprint"
                );
        }
        if (!checkpoint.manifestFingerprint().equals(manifest.manifestFingerprint())) {
            return VerificationEvent.manifestMismatch(
                event,
                checkpoint,
                actualInputFingerprint,
                manifest,
                "Projection manifest fingerprint drift"
            );
        }
        return null;
    }

    private void insertRun(
        Connection connection,
        UUID replayRunId,
        String projectionName,
        int requestedLimit,
        String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_projection_replay_runs (
                replay_run_id, projection_name, replay_mode, reason, status, requested_limit, created_at
            ) VALUES (?, ?, 'DRY_RUN', ?, 'RUNNING', ?, CURRENT_TIMESTAMP)
            """)) {
            statement.setObject(1, replayRunId);
            statement.setString(2, projectionName);
            statement.setString(3, truncate(reason, 2000));
            statement.setInt(4, requestedLimit);
            statement.executeUpdate();
        }
    }

    private List<AuthorityEventEnvelope> loadEvents(Connection connection, ReplayWindow window) throws SQLException {
        String sql = window.afterCreatedAt() == null
            ? """
                SELECT event_id, command_id, aggregate_scope, aggregate_type, aggregate_id,
                       revision, event_type, payload::text AS payload,
                       provenance::text AS provenance, created_at
                FROM authority_events
                ORDER BY created_at, event_id
                LIMIT ?
                """
            : """
                SELECT event_id, command_id, aggregate_scope, aggregate_type, aggregate_id,
                       revision, event_type, payload::text AS payload,
                       provenance::text AS provenance, created_at
                FROM authority_events
                WHERE created_at > ?
                   OR (created_at = ? AND event_id > ?)
                ORDER BY created_at, event_id
                LIMIT ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (window.afterCreatedAt() == null) {
                statement.setInt(1, window.limit());
            } else {
                Timestamp afterCreatedAt = Timestamp.from(window.afterCreatedAt());
                statement.setTimestamp(1, afterCreatedAt);
                statement.setTimestamp(2, afterCreatedAt);
                statement.setObject(3, window.afterEventId());
                statement.setInt(4, window.limit());
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

    private Checkpoint loadCheckpoint(
        Connection connection,
        String projectionName,
        UUID eventId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT input_fingerprint, output_fingerprint, projection_version, manifest_fingerprint
            FROM authority_projection_checkpoints
            WHERE projection_name = ? AND event_id = ?
            """)) {
            statement.setString(1, projectionName);
            statement.setObject(2, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new Checkpoint(
                    rows.getString("input_fingerprint"),
                    rows.getString("output_fingerprint"),
                    rows.getString("projection_version"),
                    rows.getString("manifest_fingerprint")
                );
            }
        }
    }

    private void insertRunEvent(
        Connection connection,
        UUID replayRunId,
        VerificationEvent event
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO authority_projection_replay_run_events (
                replay_run_id, event_id, event_created_at, aggregate_scope, revision, verdict,
                expected_input_fingerprint, actual_input_fingerprint, expected_output_fingerprint,
                actual_output_fingerprint, expected_manifest_fingerprint, actual_manifest_fingerprint,
                projection_version, message, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
            statement.setObject(1, replayRunId);
            statement.setObject(2, event.event().eventId());
            statement.setTimestamp(3, Timestamp.from(event.event().createdAt()));
            statement.setString(4, event.event().aggregateScope());
            statement.setLong(5, event.event().revision());
            statement.setString(6, event.verdict().name());
            statement.setString(7, event.expectedInputFingerprint());
            statement.setString(8, event.actualInputFingerprint());
            statement.setString(9, event.expectedOutputFingerprint());
            statement.setString(10, event.actualOutputFingerprint());
            statement.setString(11, event.expectedManifestFingerprint());
            statement.setString(12, event.actualManifestFingerprint());
            statement.setString(13, event.projectionVersion());
            statement.setString(14, event.message());
            statement.executeUpdate();
        }
    }

    private void updateRun(
        Connection connection,
        UUID replayRunId,
        Status status,
        RunCounters counters,
        AuthorityEventEnvelope start,
        AuthorityEventEnvelope end,
        ReplayEvidence evidence
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE authority_projection_replay_runs
            SET status = ?,
                scanned_events = ?,
                verified_events = ?,
                skipped_events = ?,
                missing_checkpoints = ?,
                mismatched_checkpoints = ?,
                replay_failures = ?,
                start_event_id = ?,
                end_event_id = ?,
                start_event_created_at = ?,
                end_event_created_at = ?,
                schema_contract_version = ?,
                schema_contract_fingerprint = ?,
                projection_manifest_fingerprint = ?,
                replay_source = ?,
                event_range_fingerprint = ?,
                verification_fingerprint = ?,
                completed_at = CURRENT_TIMESTAMP
            WHERE replay_run_id = ?
            """)) {
            statement.setString(1, status.name());
            statement.setInt(2, counters.scannedEvents());
            statement.setInt(3, counters.verifiedEvents());
            statement.setInt(4, counters.skippedEvents());
            statement.setInt(5, counters.missingCheckpoints());
            statement.setInt(6, counters.inputMismatches() + counters.outputMismatches()
                + counters.manifestMismatches());
            statement.setInt(7, counters.replayFailures());
            statement.setObject(8, start == null ? null : start.eventId());
            statement.setObject(9, end == null ? null : end.eventId());
            statement.setTimestamp(10, start == null ? null : Timestamp.from(start.createdAt()));
            statement.setTimestamp(11, end == null ? null : Timestamp.from(end.createdAt()));
            statement.setInt(12, evidence.schemaContractVersion());
            statement.setString(13, evidence.schemaContractFingerprint());
            statement.setString(14, evidence.projectionManifestFingerprint());
            statement.setString(15, evidence.replaySource());
            statement.setString(16, evidence.eventRangeFingerprint());
            statement.setString(17, evidence.verificationFingerprint());
            statement.setObject(18, replayRunId);
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
                            "Missing required authority projection replay table '" + table
                                + "'. Run data-api migrations before verifying projection replay."
                        );
                    }
                }
            }
        }
    }

    private void validateReplayRunColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'authority_projection_replay_runs'
              AND column_name = ?
            """)) {
            for (String column : REQUIRED_REPLAY_RUN_COLUMNS) {
                statement.setString(1, column);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException(
                            "Missing authority projection replay receipt column '"
                                + column + "'. Run data-api migrations before verifying projection replay."
                        );
                    }
                }
            }
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
            rows.getTimestamp("created_at").toInstant()
        );
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

    private static ReplayWindow normalizedWindow(ReplayWindow window) {
        return (window == null ? ReplayWindow.first(DEFAULT_LIMIT) : window).normalized();
    }

    private static String normalizedProjectionName(String projectionName) {
        if (projectionName == null || projectionName.isBlank()) {
            throw new IllegalArgumentException("projectionName is required");
        }
        return projectionName.trim();
    }

    private static AuthorityProjectionManifest normalizedReplayManifest(
        AuthorityEventReplayTarget target,
        String projectionName
    ) {
        AuthorityProjectionManifest manifest = target.projectionManifest();
        if (manifest == null) {
            manifest = AuthorityProjectionManifest.unversioned(projectionName);
        }
        if (!projectionName.equals(manifest.projectionName())) {
            throw new IllegalArgumentException(
                "Projection manifest name '" + manifest.projectionName()
                    + "' must match replay projectionName '" + projectionName + "'"
            );
        }
        return manifest;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private ReplayEvidence evidence(
        String projectionName,
        Status status,
        RunCounters counters,
        AuthorityProjectionManifest manifest,
        List<VerificationEvent> verifications
    ) {
        String eventRangeFingerprint = eventRangeFingerprint(verifications);
        String projectionManifestFingerprint = manifest == null ? null : manifest.manifestFingerprint();
        String verificationFingerprint = sha256(String.join("|",
            projectionName,
            status.name(),
            Integer.toString(counters.scannedEvents()),
            Integer.toString(counters.verifiedEvents()),
            Integer.toString(counters.skippedEvents()),
            Integer.toString(counters.missingCheckpoints()),
            Integer.toString(counters.inputMismatches()),
            Integer.toString(counters.outputMismatches()),
            Integer.toString(counters.manifestMismatches()),
            Integer.toString(counters.replayFailures()),
            Integer.toString(schemaContract.version()),
            schemaContract.fingerprint(),
            string(projectionManifestFingerprint),
            REPLAY_SOURCE,
            eventRangeFingerprint
        ));
        return new ReplayEvidence(
            schemaContract.version(),
            schemaContract.fingerprint(),
            projectionManifestFingerprint,
            REPLAY_SOURCE,
            eventRangeFingerprint,
            verificationFingerprint
        );
    }

    private static String eventRangeFingerprint(List<VerificationEvent> verifications) {
        StringBuilder canonical = new StringBuilder();
        for (VerificationEvent verification : verifications) {
            AuthorityEventEnvelope event = verification.event();
            canonical
                .append(event.eventId()).append('|')
                .append(event.createdAt()).append('|')
                .append(event.aggregateScope()).append('|')
                .append(event.revision()).append('|')
                .append(event.eventType()).append('|')
                .append(verification.verdict().name()).append('|')
                .append(string(verification.expectedInputFingerprint())).append('|')
                .append(string(verification.actualInputFingerprint())).append('|')
                .append(string(verification.expectedOutputFingerprint())).append('|')
                .append(string(verification.actualOutputFingerprint())).append('|')
                .append(string(verification.expectedManifestFingerprint())).append('|')
                .append(string(verification.actualManifestFingerprint())).append('|')
                .append(string(verification.projectionVersion())).append('\n');
        }
        return sha256(canonical.toString());
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority projection replay evidence", exception);
        }
    }

    /**
     * Bounded replay scan window.
     *
     * @param afterCreatedAt lower exclusive event creation timestamp, or null for the start of the log
     * @param afterEventId lower exclusive event id for events sharing {@code afterCreatedAt}
     * @param limit maximum events to verify
     */
    public record ReplayWindow(Instant afterCreatedAt, UUID afterEventId, int limit) {
        /**
         * Creates a window from the start of the authority event log.
         *
         * @param limit maximum events to verify
         * @return first-page replay window
         */
        public static ReplayWindow first(int limit) {
            return new ReplayWindow(null, null, limit);
        }

        private ReplayWindow normalized() {
            if ((afterCreatedAt == null) != (afterEventId == null)) {
                throw new IllegalArgumentException("afterCreatedAt and afterEventId must be supplied together");
            }
            int normalizedLimit = limit > 0 ? limit : DEFAULT_LIMIT;
            return new ReplayWindow(afterCreatedAt, afterEventId, normalizedLimit);
        }
    }

    /**
     * Persisted replay verification status.
     */
    public enum Status {
        /**
         * Every scanned event matched its checkpoint receipt.
         */
        VERIFIED,

        /**
         * At least one scanned event had no checkpoint receipt.
         */
        GAPS_FOUND,

        /**
         * At least one checkpoint receipt drifted from the event log or replayed output.
         */
        MISMATCH_FOUND,

        /**
         * At least one dry-run replay target failed while evaluating an event.
         */
        FAILED
    }

    /**
     * Summary returned after a replay verification run is persisted.
     *
     * @param replayRunId replay run id
     * @param projectionName projection name
     * @param status final run status
     * @param scannedEvents number of authority events scanned
     * @param verifiedEvents number of verified events
     * @param skippedEvents events ignored by the projection manifest
     * @param missingCheckpoints events without checkpoint receipts
     * @param inputMismatches checkpoint input fingerprint mismatches
     * @param outputMismatches replay output or projection version mismatches
     * @param manifestMismatches projection manifest fingerprint or event compatibility mismatches
     * @param replayFailures dry-run replay target failures
     * @param schemaContractVersion schema contract version used by the run
     * @param schemaContractFingerprint schema contract fingerprint used by the run
     * @param projectionManifestFingerprint projection manifest fingerprint used by the run
     * @param replaySource replay source attested by the run
     * @param eventRangeFingerprint deterministic fingerprint of the scanned replay event evidence
     * @param verificationFingerprint deterministic fingerprint of the replay receipt
     */
    public record ReplayVerificationResult(
        UUID replayRunId,
        String projectionName,
        Status status,
        int scannedEvents,
        int verifiedEvents,
        int skippedEvents,
        int missingCheckpoints,
        int inputMismatches,
        int outputMismatches,
        int manifestMismatches,
        int replayFailures,
        int schemaContractVersion,
        String schemaContractFingerprint,
        String projectionManifestFingerprint,
        String replaySource,
        String eventRangeFingerprint,
        String verificationFingerprint
    ) {
        /**
         * Indicates whether the scanned window matched all available replay checks.
         *
         * @return true when the final status is {@link Status#VERIFIED}
         */
        public boolean clean() {
            return status == Status.VERIFIED;
        }

        /**
         * Returns the total number of checkpoint mismatches found.
         *
         * @return input plus output mismatches
         */
        public int mismatchedCheckpoints() {
            return inputMismatches + outputMismatches + manifestMismatches;
        }
    }

    private record ReplayEvidence(
        int schemaContractVersion,
        String schemaContractFingerprint,
        String projectionManifestFingerprint,
        String replaySource,
        String eventRangeFingerprint,
        String verificationFingerprint
    ) {
    }

    private enum Verdict {
        VERIFIED,
        SKIPPED_BY_MANIFEST,
        MISSING_CHECKPOINT,
        INPUT_MISMATCH,
        OUTPUT_MISMATCH,
        MANIFEST_MISMATCH,
        REPLAY_FAILED
    }

    private record Checkpoint(
        String inputFingerprint,
        String outputFingerprint,
        String projectionVersion,
        String manifestFingerprint
    ) {
    }

    private record VerificationEvent(
        AuthorityEventEnvelope event,
        Verdict verdict,
        String expectedInputFingerprint,
        String actualInputFingerprint,
        String expectedOutputFingerprint,
        String actualOutputFingerprint,
        String expectedManifestFingerprint,
        String actualManifestFingerprint,
        String projectionVersion,
        String message
    ) {
        private static VerificationEvent verified(
            AuthorityEventEnvelope event,
            Checkpoint checkpoint,
            String actualInputFingerprint,
            String actualOutputFingerprint,
            AuthorityProjectionManifest manifest
        ) {
            return new VerificationEvent(
                event,
                Verdict.VERIFIED,
                checkpoint.inputFingerprint(),
                actualInputFingerprint,
                checkpoint.outputFingerprint(),
                actualOutputFingerprint,
                checkpoint.manifestFingerprint(),
                manifest == null ? null : manifest.manifestFingerprint(),
                checkpoint.projectionVersion(),
                ""
            );
        }

        private static VerificationEvent skippedByManifest(
            AuthorityEventEnvelope event,
            String actualInputFingerprint,
            AuthorityProjectionManifest manifest
        ) {
            return new VerificationEvent(
                event,
                Verdict.SKIPPED_BY_MANIFEST,
                null,
                actualInputFingerprint,
                null,
                null,
                manifest.manifestFingerprint(),
                manifest.manifestFingerprint(),
                manifest.projectionVersion(),
                "Projection manifest skips event type " + event.eventType()
            );
        }

        private static VerificationEvent missingCheckpoint(
            AuthorityEventEnvelope event,
            String actualInputFingerprint
        ) {
            return new VerificationEvent(
                event,
                Verdict.MISSING_CHECKPOINT,
                null,
                actualInputFingerprint,
                null,
                null,
                null,
                null,
                null,
                "No checkpoint receipt exists for projection and event"
            );
        }

        private static VerificationEvent inputMismatch(
            AuthorityEventEnvelope event,
            Checkpoint checkpoint,
            String actualInputFingerprint
        ) {
            return new VerificationEvent(
                event,
                Verdict.INPUT_MISMATCH,
                checkpoint.inputFingerprint(),
                actualInputFingerprint,
                checkpoint.outputFingerprint(),
                null,
                checkpoint.manifestFingerprint(),
                null,
                checkpoint.projectionVersion(),
                "Input fingerprint drift"
            );
        }

        private static VerificationEvent outputMismatch(
            AuthorityEventEnvelope event,
            Checkpoint checkpoint,
            String actualInputFingerprint,
            String actualOutputFingerprint,
            String message
        ) {
            return new VerificationEvent(
                event,
                Verdict.OUTPUT_MISMATCH,
                checkpoint.inputFingerprint(),
                actualInputFingerprint,
                checkpoint.outputFingerprint(),
                actualOutputFingerprint,
                checkpoint.manifestFingerprint(),
                null,
                checkpoint.projectionVersion(),
                message
            );
        }

        private static VerificationEvent manifestMismatch(
            AuthorityEventEnvelope event,
            Checkpoint checkpoint,
            String actualInputFingerprint,
            AuthorityProjectionManifest manifest,
            String message
        ) {
            return new VerificationEvent(
                event,
                Verdict.MANIFEST_MISMATCH,
                checkpoint.inputFingerprint(),
                actualInputFingerprint,
                checkpoint.outputFingerprint(),
                null,
                checkpoint.manifestFingerprint(),
                manifest.manifestFingerprint(),
                checkpoint.projectionVersion(),
                message
            );
        }

        private static VerificationEvent replayFailed(
            AuthorityEventEnvelope event,
            Checkpoint checkpoint,
            String actualInputFingerprint,
            String message
        ) {
            return new VerificationEvent(
                event,
                Verdict.REPLAY_FAILED,
                checkpoint.inputFingerprint(),
                actualInputFingerprint,
                checkpoint.outputFingerprint(),
                null,
                checkpoint.manifestFingerprint(),
                null,
                checkpoint.projectionVersion(),
                message
            );
        }
    }

    private static final class RunCounters {
        private int scannedEvents;
        private int verifiedEvents;
        private int skippedEvents;
        private int missingCheckpoints;
        private int inputMismatches;
        private int outputMismatches;
        private int manifestMismatches;
        private int replayFailures;

        private void record(Verdict verdict) {
            scannedEvents++;
            switch (verdict) {
                case VERIFIED -> verifiedEvents++;
                case SKIPPED_BY_MANIFEST -> skippedEvents++;
                case MISSING_CHECKPOINT -> missingCheckpoints++;
                case INPUT_MISMATCH -> inputMismatches++;
                case OUTPUT_MISMATCH -> outputMismatches++;
                case MANIFEST_MISMATCH -> manifestMismatches++;
                case REPLAY_FAILED -> replayFailures++;
            }
        }

        private Status status() {
            if (replayFailures > 0) {
                return Status.FAILED;
            }
            if (inputMismatches > 0 || outputMismatches > 0 || manifestMismatches > 0) {
                return Status.MISMATCH_FOUND;
            }
            if (missingCheckpoints > 0) {
                return Status.GAPS_FOUND;
            }
            return Status.VERIFIED;
        }

        private int scannedEvents() {
            return scannedEvents;
        }

        private int verifiedEvents() {
            return verifiedEvents;
        }

        private int skippedEvents() {
            return skippedEvents;
        }

        private int missingCheckpoints() {
            return missingCheckpoints;
        }

        private int inputMismatches() {
            return inputMismatches;
        }

        private int outputMismatches() {
            return outputMismatches;
        }

        private int manifestMismatches() {
            return manifestMismatches;
        }

        private int replayFailures() {
            return replayFailures;
        }
    }
}
