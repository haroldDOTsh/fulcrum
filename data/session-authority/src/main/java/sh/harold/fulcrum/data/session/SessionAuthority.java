package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;

import java.util.List;
import java.util.Objects;

public final class SessionAuthority {
    private static final String CONTRACT_NAME = "session";

    private final AuthorityCommandProcessor<SessionState, SessionCommand, SessionReceipt> processor;

    public SessionAuthority(IdempotencyLedger<SessionState, SessionReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                SessionAuthority::rejectionReceipt,
                this::apply);
    }

    public AuthorityDecision<SessionState, SessionReceipt> handle(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<SessionState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, SessionState.empty());
    }

    public static AggregateId aggregateId(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return new AggregateId("session:" + sessionId.value());
    }

    public static String cacheKey(SessionId sessionId) {
        return CONTRACT_NAME + ":" + aggregateId(sessionId).value();
    }

    private AuthorityMutationResult<SessionState, SessionReceipt> apply(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord) {
        SessionCommand payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.sessionId()))) {
            throw new IllegalArgumentException("session aggregate must be keyed by Session");
        }

        if (payload instanceof OpenSession open) {
            return open(command, currentRecord, open);
        }
        if (payload instanceof ActivateSession activate) {
            return activate(command, currentRecord, activate);
        }
        if (payload instanceof HeartbeatSession heartbeat) {
            return heartbeat(command, currentRecord, heartbeat);
        }
        if (payload instanceof CloseSession close) {
            return close(command, currentRecord, close);
        }
        if (payload instanceof ExpireSession expire) {
            return expire(command, currentRecord, expire);
        }
        throw new IllegalArgumentException("unknown Session command");
    }

    private AuthorityMutationResult<SessionState, SessionReceipt> open(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord,
            OpenSession payload) {
        if (!payload.leaseExpiresAt().isAfter(command.receivedAt())) {
            throw new IllegalArgumentException("session lease must expire after authority receipt");
        }
        if (currentRecord.state().current().isPresent()) {
            throw new IllegalStateException("Session is immutable once opened except lifecycle transitions");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, SessionChangeKind.OPENED, SessionSnapshot.from(payload, 1));
    }

    private AuthorityMutationResult<SessionState, SessionReceipt> activate(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord,
            ActivateSession payload) {
        SessionSnapshot current = liveCurrent(command, currentRecord);
        requireCurrentOwner(current, payload.ownerToken(), payload.ownerEpoch());
        if (current.status() != SessionLifecycleStatus.PREPARING) {
            throw new IllegalStateException("only preparing Session can be activated");
        }
        if (!current.leaseExpiresAt().isAfter(command.receivedAt())) {
            throw new IllegalStateException("expired Session owner cannot activate");
        }
        if (!payload.leaseExpiresAt().isAfter(command.receivedAt())) {
            throw new IllegalArgumentException("session lease must expire after authority receipt");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, SessionChangeKind.ACTIVATED, current.activate(payload));
    }

    private AuthorityMutationResult<SessionState, SessionReceipt> heartbeat(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord,
            HeartbeatSession payload) {
        SessionSnapshot current = liveCurrent(command, currentRecord);
        requireCurrentOwner(current, payload.ownerToken(), payload.ownerEpoch());
        if (!current.leaseExpiresAt().isAfter(command.receivedAt())) {
            throw new IllegalStateException("expired Session owner is fenced after lease expiry");
        }
        if (!payload.leaseExpiresAt().isAfter(command.receivedAt())) {
            throw new IllegalArgumentException("session lease must expire after authority receipt");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, SessionChangeKind.HEARTBEAT, current.heartbeat(payload));
    }

    private AuthorityMutationResult<SessionState, SessionReceipt> close(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord,
            CloseSession payload) {
        SessionSnapshot current = liveCurrent(command, currentRecord);
        requireCurrentOwner(current, payload.ownerToken(), payload.ownerEpoch());
        if (!current.leaseExpiresAt().isAfter(command.receivedAt())) {
            throw new IllegalStateException("expired Session owner is fenced after lease expiry");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, SessionChangeKind.CLOSED, current.close(payload));
    }

    private AuthorityMutationResult<SessionState, SessionReceipt> expire(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord,
            ExpireSession payload) {
        SessionSnapshot current = liveCurrent(command, currentRecord);
        if (current.leaseExpiresAt().isAfter(command.receivedAt())) {
            throw new IllegalStateException("live Session lease cannot be expired before deadline");
        }
        if (payload.expiredAt().isBefore(current.leaseExpiresAt())) {
            throw new IllegalArgumentException("expiredAt must not be before lease expiry");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, SessionChangeKind.EXPIRED, current.expire(payload));
    }

    private AuthorityMutationResult<SessionState, SessionReceipt> accepted(
            AuthorityCommand<SessionCommand> command,
            Revision revision,
            SessionChangeKind changeKind,
            SessionSnapshot snapshot) {
        SessionState state = new SessionState(snapshot);
        SessionReceipt receipt = SessionReceipt.accepted(
                snapshot,
                revision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        String aggregateKey = aggregateId(snapshot.sessionId()).value();
        return new AuthorityMutationResult<>(
                revision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, SessionChanged.from(changeKind, snapshot, revision).wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, state.wireValue(revision)),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.sessionId()), state.wireValue(revision))));
    }

    private static SessionSnapshot liveCurrent(
            AuthorityCommand<SessionCommand> command,
            AuthorityRecord<SessionState> currentRecord) {
        SessionSnapshot current = currentRecord.state().current()
                .orElseThrow(() -> new IllegalStateException("Session must be opened before lifecycle commands"));
        if (!current.sessionId().equals(command.envelope().payload().sessionId())) {
            throw new IllegalArgumentException("session command id must match current aggregate");
        }
        if (current.status() == SessionLifecycleStatus.ENDED || current.status() == SessionLifecycleStatus.FAILED) {
            throw new IllegalStateException("terminal Session cannot be mutated");
        }
        return current;
    }

    private static void requireCurrentOwner(
            SessionSnapshot current,
            SessionOwnerToken ownerToken,
            long ownerEpoch) {
        if (!current.ownerToken().equals(ownerToken) || current.ownerEpoch() != ownerEpoch) {
            throw new IllegalStateException("Session owner token or epoch is stale");
        }
    }

    private static SessionReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return SessionReceipt.rejected(reason.name());
    }
}
