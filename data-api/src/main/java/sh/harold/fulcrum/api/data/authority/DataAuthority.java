package sh.harold.fulcrum.api.data.authority;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Scale-first data contracts for Fulcrum's private authority plane.
 *
 * <p>These contracts are intentionally small: runtime plugins should read
 * snapshots or submit commands, not patch arbitrary shared documents.</p>
 */
public final class DataAuthority {
    private DataAuthority() {
    }

    public enum CommandType {
        RECORD_PLAYER_LOGIN,
        RECORD_PLAYER_LOGOUT,
        GRANT_RANK,
        REVOKE_RANK,
        START_SESSION,
        RENEW_SESSION,
        END_SESSION,
        RECORD_MATCH_START,
        RECORD_MATCH_END
    }

    public enum RejectionReason {
        NONE,
        STALE_FENCING_TOKEN,
        STALE_REVISION,
        EXPIRED_DEADLINE,
        INVALID_ACTOR,
        INVALID_SCOPE,
        STORE_UNAVAILABLE,
        VALIDATION_FAILED
    }

    public interface CommandPort {
        CompletionStage<CommandResult> submit(CommandEnvelope command);
    }

    public record CommandEnvelope(
        UUID commandId,
        CommandType type,
        String actorId,
        String scope,
        String idempotencyKey,
        long deadlineEpochMillis,
        String fencingToken,
        long expectedRevision,
        Map<String, Object> payload
    ) {
        public CommandEnvelope {
            if (commandId == null) {
                throw new IllegalArgumentException("commandId is required");
            }
            if (type == null) {
                throw new IllegalArgumentException("type is required");
            }
            if (actorId == null || actorId.isBlank()) {
                throw new IllegalArgumentException("actorId is required");
            }
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("scope is required");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record CommandResult(
        UUID commandId,
        boolean accepted,
        long revision,
        RejectionReason rejectionReason,
        String message
    ) {
        public CommandResult {
            if (commandId == null) {
                throw new IllegalArgumentException("commandId is required");
            }
            rejectionReason = rejectionReason == null ? RejectionReason.NONE : rejectionReason;
            message = message == null ? "" : message;
        }
    }
}
