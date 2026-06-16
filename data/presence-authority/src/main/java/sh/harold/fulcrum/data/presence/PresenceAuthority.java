package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;
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

public final class PresenceAuthority {
    private static final String CONTRACT_NAME = "presence";

    private final AuthorityCommandProcessor<PresenceState, PresenceCommand, PresenceReceipt> processor;

    public PresenceAuthority(IdempotencyLedger<PresenceState, PresenceReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                PresenceAuthority::rejectionReceipt,
                this::apply);
    }

    public AuthorityDecision<PresenceState, PresenceReceipt> handle(
            AuthorityCommand<PresenceCommand> command,
            AuthorityRecord<PresenceState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<PresenceState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, PresenceState.empty());
    }

    public static AggregateId aggregateId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new AggregateId("subject:" + subjectId.value());
    }

    public static String cacheKey(SubjectId subjectId) {
        return CONTRACT_NAME + ":" + aggregateId(subjectId).value();
    }

    private AuthorityMutationResult<PresenceState, PresenceReceipt> apply(
            AuthorityCommand<PresenceCommand> command,
            AuthorityRecord<PresenceState> currentRecord) {
        PresenceCommand payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.subjectId()))) {
            throw new IllegalArgumentException("presence aggregate must be keyed by Subject");
        }

        if (payload instanceof ClaimPresence claim) {
            return claim(command, currentRecord, claim);
        }
        if (payload instanceof HeartbeatPresence heartbeat) {
            return heartbeat(command, currentRecord, heartbeat);
        }
        if (payload instanceof ReleasePresence release) {
            return release(command, currentRecord, release);
        }
        throw new IllegalArgumentException("unknown Presence command");
    }

    private AuthorityMutationResult<PresenceState, PresenceReceipt> claim(
            AuthorityCommand<PresenceCommand> command,
            AuthorityRecord<PresenceState> currentRecord,
            ClaimPresence payload) {
        if (!payload.expiresAt().isAfter(command.receivedAt())) {
            throw new IllegalArgumentException("presence lease must expire after authority receipt");
        }
        currentRecord.state().current()
                .filter(current -> current.status() == PresenceLifecycleStatus.LIVE)
                .filter(current -> current.expiresAt().isAfter(command.receivedAt()))
                .ifPresent(current -> {
                    throw new IllegalStateException("live Presence must be released or expired before a new claim");
                });

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        long nextOwnerEpoch = currentRecord.state().current()
                .map(current -> current.ownerEpoch() + 1)
                .orElse(1L);
        return accepted(command, nextRevision, PresenceChangeKind.CLAIMED, PresenceSnapshot.from(payload, nextOwnerEpoch));
    }

    private AuthorityMutationResult<PresenceState, PresenceReceipt> heartbeat(
            AuthorityCommand<PresenceCommand> command,
            AuthorityRecord<PresenceState> currentRecord,
            HeartbeatPresence payload) {
        PresenceSnapshot current = liveCurrent(command, currentRecord);
        requireCurrentOwner(current, payload.ownerToken(), payload.ownerEpoch());
        if (!current.expiresAt().isAfter(command.receivedAt())) {
            throw new IllegalStateException("stale Presence owner is fenced after lease expiry");
        }
        if (!payload.expiresAt().isAfter(command.receivedAt())) {
            throw new IllegalArgumentException("presence lease must expire after authority receipt");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, PresenceChangeKind.HEARTBEAT, current.heartbeat(payload));
    }

    private AuthorityMutationResult<PresenceState, PresenceReceipt> release(
            AuthorityCommand<PresenceCommand> command,
            AuthorityRecord<PresenceState> currentRecord,
            ReleasePresence payload) {
        PresenceSnapshot current = liveCurrent(command, currentRecord);
        requireCurrentOwner(current, payload.ownerToken(), payload.ownerEpoch());
        if (!current.expiresAt().isAfter(command.receivedAt())) {
            throw new IllegalStateException("stale Presence owner is fenced after lease expiry");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, PresenceChangeKind.RELEASED, current.release(payload));
    }

    private AuthorityMutationResult<PresenceState, PresenceReceipt> accepted(
            AuthorityCommand<PresenceCommand> command,
            Revision revision,
            PresenceChangeKind changeKind,
            PresenceSnapshot snapshot) {
        PresenceState state = new PresenceState(snapshot);
        PresenceReceipt receipt = PresenceReceipt.accepted(
                snapshot,
                revision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        String aggregateKey = aggregateId(snapshot.subjectId()).value();
        return new AuthorityMutationResult<>(
                revision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, PresenceChanged.from(changeKind, snapshot, revision).wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, state.wireValue(revision)),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.subjectId()), state.wireValue(revision))));
    }

    private static PresenceSnapshot liveCurrent(
            AuthorityCommand<PresenceCommand> command,
            AuthorityRecord<PresenceState> currentRecord) {
        PresenceSnapshot current = currentRecord.state().current()
                .orElseThrow(() -> new IllegalStateException("Presence must be claimed before lifecycle commands"));
        if (!current.subjectId().equals(command.envelope().payload().subjectId())) {
            throw new IllegalArgumentException("presence command subject must match current aggregate");
        }
        if (current.status() != PresenceLifecycleStatus.LIVE) {
            throw new IllegalStateException("released Presence cannot be mutated");
        }
        return current;
    }

    private static void requireCurrentOwner(
            PresenceSnapshot current,
            PresenceOwnerToken ownerToken,
            long ownerEpoch) {
        if (!current.ownerToken().equals(ownerToken) || current.ownerEpoch() != ownerEpoch) {
            throw new IllegalStateException("Presence owner token or epoch is stale");
        }
    }

    private static PresenceReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return PresenceReceipt.rejected(reason.name());
    }
}
