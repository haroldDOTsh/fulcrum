package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;

import com.google.gson.Gson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Records durable command ingress frames before delegating to the authority writer.
 */
public final class PostgresLoggedAuthorityCommandPort implements AuthorityCommandJournal.Recorder {
    private static final List<String> REQUIRED_TABLES = List.of("authority_command_ingress_log");
    private static final String REPLAYABLE = "REPLAYABLE";
    private static final String NOT_REPLAYABLE = "NOT_REPLAYABLE";

    private final PostgresConnectionAdapter connectionAdapter;
    private final DataAuthority.CommandPort delegate;
    private final Gson gson = new Gson();

    /**
     * Creates a command-port decorator that records ingress and terminal outcomes.
     *
     * @param connectionAdapter Postgres connection adapter
     * @param delegate authority command writer
     */
    public PostgresLoggedAuthorityCommandPort(
        PostgresConnectionAdapter connectionAdapter,
        DataAuthority.CommandPort delegate
    ) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
                                + "'. Run data-api migrations before enabling command ingress logging."
                        );
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to validate authority command ingress schema", exception);
        }
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            recordReceived(command);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(storeUnavailable(command, exception));
        }

        CompletionStage<DataAuthority.CommandResult> delegated;
        try {
            delegated = delegate.submit(command);
        } catch (RuntimeException exception) {
            DataAuthority.CommandResult failed = storeUnavailable(command, exception);
            recordFailed(command, command.commandId(), failed, exception);
            return CompletableFuture.completedFuture(failed);
        }

        return delegated.handle((result, failure) -> {
            if (failure != null) {
                DataAuthority.CommandResult failed = storeUnavailable(command, failure);
                recordFailed(command, command.commandId(), failed, failure);
                return failed;
            }
            DataAuthority.CommandResult normalized = result == null
                ? storeUnavailable(command, new IllegalStateException("Authority returned no result"))
                : result;
            recordCompleted(command, normalized);
            return normalized;
        });
    }

    private void recordReceived(DataAuthority.AuthorityCommand command) {
        AuthorityCommandFingerprints.Fingerprint fingerprint = AuthorityCommandFingerprints.fingerprint(command);
        AuthorityCommandFrame frame = AuthorityCommandFrame.fromCommand(command);
        AuthorityCommandRoute route = frame.route();
        AuthorityCommandLane writerLane = AuthorityCommandLane.fromRoute(
            route,
            AuthorityCommandLane.DEFAULT_LANE_COUNT
        );
        DataAuthority.CommandProvenance provenance = command.provenance();
        AuthorityCommandGuardEvidence.GuardEvidence guardEvidence =
            AuthorityCommandGuardEvidence.received(command, frame, fingerprint);
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO authority_command_ingress_log (
                     command_id, command_type, aggregate_scope, idempotency_key,
                     claimed_actor, verified_principal,
                     command_domain, command_topic, partition_key,
                     writer_lane_count, writer_lane, writer_lane_key_fingerprint, writer_lane_fencing_scope,
                     origin_node, authority_route, provider_kind, contract_version,
                     manifest_payload, command_payload, payload_hash, command_fingerprint,
                     guard_evidence, guard_evidence_fingerprint,
                     status, replay_eligibility, result_payload, received_at, updated_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?,
                     ?::jsonb, ?,
                     'RECEIVED', 'REPLAYABLE', '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                 ON CONFLICT (command_id) DO NOTHING
                 """)) {
            statement.setObject(1, command.commandId());
            statement.setString(2, command.type().name());
            statement.setString(3, command.scope());
            statement.setString(4, command.idempotencyKey());
            statement.setString(5, command.actorId());
            statement.setString(6, provenance.verifiedPrincipal());
            statement.setString(7, route.domain());
            statement.setString(8, route.commandTopic());
            statement.setString(9, route.partitionKey());
            statement.setInt(10, writerLane.laneCount());
            statement.setInt(11, writerLane.lane());
            statement.setString(12, writerLane.laneKeyFingerprint());
            statement.setString(13, writerLane.fencingScope());
            statement.setString(14, provenance.originNode());
            statement.setString(15, provenance.authorityRoute());
            statement.setString(16, provenance.providerKind());
            statement.setInt(17, provenance.contractVersion());
            statement.setString(18, gson.toJson(frame.manifestPayload()));
            statement.setString(19, gson.toJson(frame.payload()));
            statement.setString(20, fingerprint.payloadHash());
            statement.setString(21, fingerprint.commandFingerprint());
            statement.setString(22, gson.toJson(guardEvidence.payload()));
            statement.setString(23, guardEvidence.fingerprint());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record authority command ingress " + command.commandId(), exception);
        }
    }

    private void recordCompleted(DataAuthority.AuthorityCommand command, DataAuthority.CommandResult result) {
        String status = result.accepted() ? "APPLIED" : "REJECTED";
        String replayEligibility = replayEligibility(result);
        AuthorityCommandGuardEvidence.GuardEvidence guardEvidence =
            AuthorityCommandGuardEvidence.terminal(command, result, replayEligibility);
        AuthorityWriterClaimToken writerClaim = writerClaimToken(result.settlement().fencingToken());
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE authority_command_ingress_log
                 SET status = ?,
                     accepted = ?,
                     rejection_reason = ?,
                     result_revision = ?,
                     result_message = ?,
                     result_payload = ?::jsonb,
                     replay_eligibility = ?,
                     guard_evidence = ?::jsonb,
                     guard_evidence_fingerprint = ?,
                     writer_claim_epoch = ?,
                     writer_claim_id = ?,
                     writer_claim_fingerprint = ?,
                     failure_message = NULL,
                     completed_at = CURRENT_TIMESTAMP,
                     updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ?
                 """)) {
            statement.setString(1, status);
            statement.setBoolean(2, result.accepted());
            statement.setString(3, result.rejectionReason().name());
            statement.setLong(4, result.revision());
            statement.setString(5, truncate(result.message(), 2000));
            statement.setString(6, gson.toJson(result.settlement().payload()));
            statement.setString(7, replayEligibility);
            statement.setString(8, gson.toJson(guardEvidence.payload()));
            statement.setString(9, guardEvidence.fingerprint());
            if (writerClaim == null) {
                statement.setNull(10, Types.BIGINT);
                statement.setNull(11, Types.OTHER);
                statement.setNull(12, Types.VARCHAR);
            } else {
                statement.setLong(10, writerClaim.epoch());
                statement.setObject(11, writerClaim.claimId());
                statement.setString(12, writerClaim.claimFingerprint());
            }
            statement.setObject(13, command.commandId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record authority command outcome " + command.commandId(), exception);
        }
    }

    private void recordFailed(
        DataAuthority.AuthorityCommand command,
        UUID commandId,
        DataAuthority.CommandResult result,
        Throwable failure
    ) {
        AuthorityCommandGuardEvidence.GuardEvidence guardEvidence = command == null
            ? null
            : AuthorityCommandGuardEvidence.failure(command, result, failure, REPLAYABLE);
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE authority_command_ingress_log
                 SET status = 'FAILED',
                     accepted = FALSE,
                     rejection_reason = ?,
                     result_revision = ?,
                     result_message = ?,
                     result_payload = ?::jsonb,
                     replay_eligibility = ?,
                     guard_evidence = COALESCE(?::jsonb, guard_evidence),
                     guard_evidence_fingerprint = COALESCE(?, guard_evidence_fingerprint),
                     failure_message = ?,
                     completed_at = CURRENT_TIMESTAMP,
                     updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ?
                 """)) {
            statement.setString(1, DataAuthority.RejectionReason.STORE_UNAVAILABLE.name());
            statement.setLong(2, result.revision());
            statement.setString(3, truncate(result.message(), 2000));
            statement.setString(4, gson.toJson(result.settlement().payload()));
            statement.setString(5, REPLAYABLE);
            statement.setString(6, guardEvidence == null ? null : gson.toJson(guardEvidence.payload()));
            statement.setString(7, guardEvidence == null ? null : guardEvidence.fingerprint());
            statement.setString(8, truncate(failure.getMessage(), 2000));
            statement.setObject(9, commandId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record authority command failure " + commandId, exception);
        }
    }

    private static String replayEligibility(DataAuthority.CommandResult result) {
        return result.rejectionReason() == DataAuthority.RejectionReason.STORE_UNAVAILABLE
            ? REPLAYABLE
            : NOT_REPLAYABLE;
    }

    private static AuthorityWriterClaimToken writerClaimToken(String fencingToken) {
        try {
            return AuthorityWriterClaimToken.parse(fencingToken);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static DataAuthority.CommandResult storeUnavailable(
        DataAuthority.AuthorityCommand command,
        Throwable failure
    ) {
        return new DataAuthority.CommandResult(
            command.commandId(),
            false,
            command.expectedRevision(),
            DataAuthority.RejectionReason.STORE_UNAVAILABLE,
            "Authority command ingress failed: " + failure.getMessage()
        );
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
