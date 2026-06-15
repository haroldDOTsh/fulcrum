package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Query and replay access for durable authority command ingress rows.
 */
public final class PostgresAuthorityCommandIngressLog implements AuthorityCommandJournal.ReplayReader<
    PostgresAuthorityCommandIngressLog.CommandIngressEntry,
    PostgresAuthorityCommandIngressLog.ReplayResult
> {
    private static final List<String> REQUIRED_TABLES = List.of("authority_command_ingress_log");

    private final PostgresConnectionAdapter connectionAdapter;
    private final Gson gson = new Gson();

    /**
     * Creates a Postgres-backed ingress log.
     *
     * @param connectionAdapter Postgres connection adapter
     */
    public PostgresAuthorityCommandIngressLog(PostgresConnectionAdapter connectionAdapter) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
    }

    /**
     * Verifies that command ingress tables exist.
     */
    @Override
    public void validateSchema() {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : REQUIRED_TABLES) {
                statement.setString(1, table);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new IllegalStateException(
                            "Missing required authority command ingress table '" + table
                                + "'. Run data-api migrations before using command ingress replay."
                        );
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority command ingress schema", exception);
        }
    }

    /**
     * Finds one recorded command ingress row.
     *
     * @param commandId command id
     * @return entry when present
     */
    @Override
    public Optional<CommandIngressEntry> find(UUID commandId) {
        Objects.requireNonNull(commandId, "commandId");
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT command_id, command_type, aggregate_scope, idempotency_key,
                        claimed_actor, verified_principal,
                        command_domain, command_topic, partition_key,
                        writer_lane_count, writer_lane, writer_lane_key_fingerprint, writer_lane_fencing_scope,
                        writer_claim_epoch, writer_claim_id, writer_claim_fingerprint,
                        status, accepted, rejection_reason, result_revision, result_message,
                        replay_eligibility, result_payload::text AS result_payload,
                        guard_evidence::text AS guard_evidence, guard_evidence_fingerprint,
                        failure_message, replay_attempts, payload_hash, command_fingerprint,
                        manifest_payload::text AS manifest_payload, command_payload::text AS command_payload,
                        received_at, completed_at, last_replayed_at, updated_at
                 FROM authority_command_ingress_log
                 WHERE command_id = ?
                 """)) {
            statement.setObject(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(entry(rows));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read authority command ingress " + commandId, exception);
        }
    }

    /**
     * Finds the newest unresolved or store-failed ingress rows.
     *
     * @param limit maximum rows to return
     * @return replay candidates
     */
    @Override
    public List<CommandIngressEntry> findReplayCandidates(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT command_id, command_type, aggregate_scope, idempotency_key,
                        claimed_actor, verified_principal,
                        command_domain, command_topic, partition_key,
                        writer_lane_count, writer_lane, writer_lane_key_fingerprint, writer_lane_fencing_scope,
                        writer_claim_epoch, writer_claim_id, writer_claim_fingerprint,
                        status, accepted, rejection_reason, result_revision, result_message,
                        replay_eligibility, result_payload::text AS result_payload,
                        guard_evidence::text AS guard_evidence, guard_evidence_fingerprint,
                        failure_message, replay_attempts, payload_hash, command_fingerprint,
                        manifest_payload::text AS manifest_payload, command_payload::text AS command_payload,
                        received_at, completed_at, last_replayed_at, updated_at
                 FROM authority_command_ingress_log
                 WHERE replay_eligibility = 'REPLAYABLE'
                 ORDER BY updated_at DESC
                 LIMIT ?
                 """)) {
            statement.setInt(1, boundedLimit);
            try (ResultSet rows = statement.executeQuery()) {
                java.util.ArrayList<CommandIngressEntry> entries = new java.util.ArrayList<>();
                while (rows.next()) {
                    entries.add(entry(rows));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read authority command replay candidates", exception);
        }
    }

    /**
     * Replays a stored ingress frame through the supplied command port when it is safe to retry.
     *
     * @param commandId command id
     * @param commandPort authority command port
     * @return replay result
     */
    @Override
    public CompletionStage<ReplayResult> replay(UUID commandId, DataAuthority.CommandPort commandPort) {
        Objects.requireNonNull(commandPort, "commandPort");
        Optional<CommandIngressEntry> maybeEntry = find(commandId);
        if (maybeEntry.isEmpty()) {
            return CompletableFuture.completedFuture(ReplayResult.notFound(commandId));
        }
        CommandIngressEntry entry = maybeEntry.get();
        if (!entry.replayable()) {
            return CompletableFuture.completedFuture(ReplayResult.notReplayable(entry));
        }
        if (!entry.routeMatchesCommand()) {
            String message = "Stored command route no longer matches command material";
            recordReplayQuarantine(entry, message);
            return CompletableFuture.completedFuture(ReplayResult.notReplayable(entry, message));
        }
        if (!entry.laneMatchesCommand()) {
            String message = "Stored command writer lane no longer matches command material";
            recordReplayQuarantine(entry, message);
            return CompletableFuture.completedFuture(ReplayResult.notReplayable(entry, message));
        }
        String topologyMismatch = AuthorityTopologyEvidence.replayMismatch(entry.command(), entry.guardEvidence());
        if (topologyMismatch != null) {
            recordReplayQuarantine(entry, topologyMismatch);
            return CompletableFuture.completedFuture(ReplayResult.notReplayable(entry, topologyMismatch));
        }
        recordReplayAttempt(commandId);
        return commandPort.submit(entry.command())
            .thenApply(result -> ReplayResult.submitted(entry, result));
    }

    private void recordReplayQuarantine(CommandIngressEntry entry, String message) {
        Map<String, Object> evidence = replayQuarantineEvidence(entry, message);
        String evidenceFingerprint = AuthorityCommandFingerprints.hash(
            AuthorityCommandFingerprints.canonicalJson(evidence)
        );
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE authority_command_ingress_log
                 SET status = 'QUARANTINED',
                     accepted = FALSE,
                     rejection_reason = ?,
                     result_message = ?,
                     replay_eligibility = 'NOT_REPLAYABLE',
                     guard_evidence = ?::jsonb,
                     guard_evidence_fingerprint = ?,
                     failure_message = ?,
                     completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
                     updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ?
                 """)) {
            statement.setString(1, DataAuthority.RejectionReason.VALIDATION_FAILED.name());
            statement.setString(2, truncate(message, 2000));
            statement.setString(3, gson.toJson(evidence));
            statement.setString(4, evidenceFingerprint);
            statement.setString(5, truncate(message, 2000));
            statement.setObject(6, entry.commandId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Failed to quarantine authority command replay " + entry.commandId(),
                exception
            );
        }
    }

    private void recordReplayAttempt(UUID commandId) {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE authority_command_ingress_log
                 SET replay_attempts = replay_attempts + 1,
                     last_replayed_at = CURRENT_TIMESTAMP,
                     updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ?
                 """)) {
            statement.setObject(1, commandId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record authority command replay attempt " + commandId, exception);
        }
    }

    private CommandIngressEntry entry(ResultSet rows) throws SQLException {
        AuthorityCommandFrame frame = AuthorityCommandFrame.fromPayloads(
            jsonMap(rows.getString("manifest_payload")),
            jsonMap(rows.getString("command_payload"))
        );
        DataAuthority.AuthorityCommand command = frame.toCommand();
        AuthorityCommandRoute frameRoute = frame.route();
        CommandIngressStatus status = CommandIngressStatus.valueOf(rows.getString("status"));
        DataAuthority.RejectionReason reason = rejectionReason(rows.getString("rejection_reason"));
        long resultRevision = rows.getLong("result_revision");
        DataAuthority.CommandSettlement settlement = DataAuthority.CommandSettlement.fromPayload(
            jsonMap(rows.getString("result_payload")),
            DataAuthority.CommandSettlement.unsettled(resultRevision)
        );
        return new CommandIngressEntry(
            rows.getObject("command_id", UUID.class),
            DataAuthority.CommandType.valueOf(rows.getString("command_type")),
            rows.getString("aggregate_scope"),
            rows.getString("idempotency_key"),
            rows.getString("claimed_actor"),
            rows.getString("verified_principal"),
            firstKnown(rows.getString("command_domain"), frameRoute.domain()),
            firstKnown(rows.getString("command_topic"), frameRoute.commandTopic()),
            firstKnown(rows.getString("partition_key"), frameRoute.partitionKey()),
            rows.getInt("writer_lane_count"),
            rows.getInt("writer_lane"),
            rows.getString("writer_lane_key_fingerprint"),
            rows.getString("writer_lane_fencing_scope"),
            longValue(rows, "writer_claim_epoch", 0L),
            rows.getObject("writer_claim_id", UUID.class),
            rows.getString("writer_claim_fingerprint"),
            status,
            rows.getObject("accepted", Boolean.class),
            reason,
            resultRevision,
            rows.getString("result_message"),
            settlement,
            replayEligibility(rows.getString("replay_eligibility"), status, reason),
            jsonMap(rows.getString("guard_evidence")),
            rows.getString("guard_evidence_fingerprint"),
            rows.getString("failure_message"),
            rows.getInt("replay_attempts"),
            rows.getString("payload_hash"),
            rows.getString("command_fingerprint"),
            instant(rows.getTimestamp("received_at")),
            instant(rows.getTimestamp("completed_at")),
            instant(rows.getTimestamp("last_replayed_at")),
            instant(rows.getTimestamp("updated_at")),
            command
        );
    }

    private static Map<String, Object> replayQuarantineEvidence(CommandIngressEntry entry, String message) {
        Map<String, Object> evidence = new LinkedHashMap<>(entry.guardEvidence());
        AuthorityCommandRoute expectedRoute = AuthorityCommandRoute.fromCommand(entry.command());
        AuthorityCommandLane expectedLane = AuthorityCommandLane.fromRoute(expectedRoute, entry.writerLaneCount());
        Map<String, Object> replayQuarantine = new LinkedHashMap<>();
        replayQuarantine.put("reason", firstKnown(message, "Stored command cannot be replayed safely"));
        replayQuarantine.put("status", CommandIngressStatus.QUARANTINED.name());
        replayQuarantine.put("replayEligibility", ReplayEligibility.NOT_REPLAYABLE.name());
        replayQuarantine.put("expectedRoute", expectedRoute.payload());
        replayQuarantine.put("storedRoute", Map.of(
            "domain", firstKnown(entry.commandDomain(), "unknown"),
            "commandTopic", firstKnown(entry.commandTopic(), "unknown"),
            "partitionKey", firstKnown(entry.partitionKey(), "unknown")
        ));
        replayQuarantine.put("expectedWriterLane", expectedLane.payload());
        replayQuarantine.put("storedWriterLane", Map.of(
            "laneCount", entry.writerLaneCount(),
            "lane", entry.writerLane(),
            "laneKeyFingerprint", firstKnown(entry.writerLaneKeyFingerprint(), "missing"),
            "fencingScope", firstKnown(entry.writerLaneFencingScope(), "unknown")
        ));
        replayQuarantine.put("storedWriterClaim", Map.of(
            "epoch", entry.writerClaimEpoch(),
            "claimId", entry.writerClaimId() == null ? "missing" : entry.writerClaimId().toString(),
            "claimFingerprint", firstKnown(entry.writerClaimFingerprint(), "missing")
        ));
        replayQuarantine.put("expectedTopology", AuthorityTopologyEvidence.forCommand(entry.command(), expectedRoute));
        replayQuarantine.put("storedTopology", AuthorityTopologyEvidence.storedTopology(entry.guardEvidence()));
        replayQuarantine.put("commandFingerprint", firstKnown(entry.commandFingerprint(), "missing"));
        replayQuarantine.put("replayAttempts", entry.replayAttempts());
        evidence.put("phase", "REPLAY_QUARANTINE");
        evidence.put("replayQuarantine", Map.copyOf(replayQuarantine));
        return Map.copyOf(evidence);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<?, ?> parsed = gson.fromJson(json, Map.class);
        if (parsed == null || parsed.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        parsed.forEach((key, value) -> {
            if (key != null) {
                values.put(key.toString(), value);
            }
        });
        return values;
    }

    private static DataAuthority.RejectionReason rejectionReason(String value) {
        if (value == null || value.isBlank()) {
            return DataAuthority.RejectionReason.NONE;
        }
        return DataAuthority.RejectionReason.valueOf(value);
    }

    private static ReplayEligibility replayEligibility(
        String value,
        CommandIngressStatus status,
        DataAuthority.RejectionReason rejectionReason
    ) {
        if (value == null || value.isBlank()) {
            return status == CommandIngressStatus.RECEIVED
                || status == CommandIngressStatus.FAILED
                || rejectionReason == DataAuthority.RejectionReason.STORE_UNAVAILABLE
                ? ReplayEligibility.REPLAYABLE
                : ReplayEligibility.NOT_REPLAYABLE;
        }
        return ReplayEligibility.valueOf(value);
    }

    private static String firstKnown(String value, String fallback) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value) ? fallback : value;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static long longValue(ResultSet rows, String column, long fallback) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? fallback : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * Terminal state recorded for an ingress frame.
     */
    public enum CommandIngressStatus implements AuthorityCommandJournal.JournalStatus {
        RECEIVED,
        APPLIED,
        REJECTED,
        FAILED,
        QUARANTINED
    }

    /**
     * Durable replay verdict for an ingress frame.
     */
    public enum ReplayEligibility implements AuthorityCommandJournal.ReplayVerdict {
        REPLAYABLE,
        NOT_REPLAYABLE
    }

    /**
     * A persisted command ingress frame plus its current terminal state.
     *
     * @param commandId command id
     * @param commandType command type
     * @param aggregateScope aggregate scope
     * @param idempotencyKey idempotency key
     * @param claimedActor caller-claimed domain actor
     * @param verifiedPrincipal transport-verified principal
     * @param commandDomain command route domain
     * @param commandTopic command route topic
     * @param partitionKey command route partition key
     * @param writerLaneCount deterministic writer lane count
     * @param writerLane deterministic writer lane
     * @param writerLaneKeyFingerprint deterministic writer lane key fingerprint
     * @param writerLaneFencingScope deterministic writer lane fencing scope
     * @param writerClaimEpoch persisted writer claim epoch, or 0 when absent
     * @param writerClaimId persisted writer claim id, or null when absent
     * @param writerClaimFingerprint persisted writer claim fingerprint, or empty when absent
     * @param status ingress status
     * @param accepted terminal acceptance flag
     * @param rejectionReason terminal rejection reason
     * @param resultRevision result revision
     * @param resultMessage result message
     * @param settlement terminal settlement payload
     * @param replayEligibility durable replay verdict
     * @param guardEvidence persisted guard-decision evidence
     * @param guardEvidenceFingerprint stable fingerprint of guard evidence
     * @param failureMessage failure message
     * @param replayAttempts number of replay attempts
     * @param payloadHash canonical payload hash
     * @param commandFingerprint canonical command fingerprint
     * @param receivedAt ingress timestamp
     * @param completedAt terminal outcome timestamp
     * @param lastReplayedAt last replay timestamp
     * @param updatedAt row update timestamp
     * @param command reconstructable command frame
     */
    public record CommandIngressEntry(
        UUID commandId,
        DataAuthority.CommandType commandType,
        String aggregateScope,
        String idempotencyKey,
        String claimedActor,
        String verifiedPrincipal,
        String commandDomain,
        String commandTopic,
        String partitionKey,
        int writerLaneCount,
        int writerLane,
        String writerLaneKeyFingerprint,
        String writerLaneFencingScope,
        long writerClaimEpoch,
        UUID writerClaimId,
        String writerClaimFingerprint,
        CommandIngressStatus status,
        Boolean accepted,
        DataAuthority.RejectionReason rejectionReason,
        long resultRevision,
        String resultMessage,
        DataAuthority.CommandSettlement settlement,
        ReplayEligibility replayEligibility,
        Map<String, Object> guardEvidence,
        String guardEvidenceFingerprint,
        String failureMessage,
        int replayAttempts,
        String payloadHash,
        String commandFingerprint,
        Instant receivedAt,
        Instant completedAt,
        Instant lastReplayedAt,
        Instant updatedAt,
        DataAuthority.AuthorityCommand command
    ) implements AuthorityCommandJournal.Entry {
        public CommandIngressEntry {
            guardEvidence = guardEvidence == null ? Map.of() : Map.copyOf(guardEvidence);
            guardEvidenceFingerprint = guardEvidenceFingerprint == null ? "" : guardEvidenceFingerprint;
            writerLaneKeyFingerprint = writerLaneKeyFingerprint == null ? "" : writerLaneKeyFingerprint;
            writerLaneFencingScope = writerLaneFencingScope == null ? "" : writerLaneFencingScope;
            writerClaimFingerprint = writerClaimFingerprint == null ? "" : writerClaimFingerprint;
        }

        /**
         * Indicates whether this entry can be safely submitted again without changing command semantics.
         *
         * @return true when replay is allowed
         */
        public boolean replayable() {
            return replayEligibility == ReplayEligibility.REPLAYABLE;
        }

        public boolean routeMatchesCommand() {
            AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
            return route.domain().equals(commandDomain)
                && route.commandTopic().equals(commandTopic)
                && route.partitionKey().equals(partitionKey);
        }

        public boolean laneMatchesCommand() {
            AuthorityCommandLane expectedCommandLane = AuthorityCommandLane.fromCommand(command, writerLaneCount);
            AuthorityCommandLane expectedStoredRouteLane = AuthorityCommandLane.fromRoute(storedRoute(), writerLaneCount);
            return laneMatches(expectedCommandLane) && laneMatches(expectedStoredRouteLane);
        }

        private AuthorityCommandRoute storedRoute() {
            return new AuthorityCommandRoute(
                commandDomain,
                commandTopic,
                null,
                null,
                partitionKey
            );
        }

        private boolean laneMatches(AuthorityCommandLane expectedLane) {
            return expectedLane.lane() == writerLane
                && expectedLane.laneKeyFingerprint().equals(writerLaneKeyFingerprint)
                && expectedLane.fencingScope().equals(writerLaneFencingScope);
        }
    }

    /**
     * Result of attempting to replay a command ingress frame.
     *
     * @param commandId command id
     * @param submitted whether the command was submitted
     * @param message replay decision message
     * @param entry stored ingress entry
     * @param commandResult command result when submitted
     */
    public record ReplayResult(
        UUID commandId,
        boolean submitted,
        String message,
        CommandIngressEntry entry,
        DataAuthority.CommandResult commandResult
    ) implements AuthorityCommandJournal.Replay {
        private static ReplayResult notFound(UUID commandId) {
            return new ReplayResult(commandId, false, "Command ingress row was not found", null, null);
        }

        private static ReplayResult notReplayable(CommandIngressEntry entry) {
            return notReplayable(
                entry,
                "Command ingress row is not replayable from eligibility " + entry.replayEligibility()
            );
        }

        private static ReplayResult notReplayable(CommandIngressEntry entry, String message) {
            return new ReplayResult(
                entry.commandId(),
                false,
                message,
                entry,
                null
            );
        }

        private static ReplayResult submitted(
            CommandIngressEntry entry,
            DataAuthority.CommandResult commandResult
        ) {
            return new ReplayResult(entry.commandId(), true, "Command replay submitted", entry, commandResult);
        }
    }
}
