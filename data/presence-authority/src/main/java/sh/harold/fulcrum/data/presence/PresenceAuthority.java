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

    private final AuthorityCommandProcessor<PresenceState, ClaimPresence, PresenceReceipt> processor;

    public PresenceAuthority(IdempotencyLedger<PresenceState, PresenceReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                PresenceAuthority::rejectionReceipt,
                this::claim);
    }

    public AuthorityDecision<PresenceState, PresenceReceipt> handle(
            AuthorityCommand<ClaimPresence> command,
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

    private AuthorityMutationResult<PresenceState, PresenceReceipt> claim(
            AuthorityCommand<ClaimPresence> command,
            AuthorityRecord<PresenceState> currentRecord) {
        ClaimPresence payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.subjectId()))) {
            throw new IllegalArgumentException("presence aggregate must be keyed by Subject");
        }
        if (!payload.expiresAt().isAfter(command.receivedAt())) {
            throw new IllegalArgumentException("presence lease must expire after authority receipt");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        PresenceSnapshot snapshot = PresenceSnapshot.from(payload);
        PresenceState state = new PresenceState(snapshot);
        PresenceReceipt receipt = PresenceReceipt.accepted(
                payload.presenceId(),
                payload.subjectId(),
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        String aggregateKey = aggregateId(payload.subjectId()).value();
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, PresenceClaimed.from(snapshot, nextRevision).wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, state.wireValue(nextRevision)),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(payload.subjectId()), state.wireValue(nextRevision))));
    }

    private static PresenceReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return PresenceReceipt.rejected(reason.name());
    }
}
