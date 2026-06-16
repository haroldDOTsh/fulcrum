package sh.harold.fulcrum.standard.punishment;

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
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;

import java.util.List;
import java.util.Objects;

public final class PunishmentAuthority {
    private final AuthorityCommandProcessor<PunishmentState, IssuePunishment, PunishmentReceipt> processor;

    public PunishmentAuthority(IdempotencyLedger<PunishmentState, PunishmentReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                PunishmentAuthority::rejectionReceipt,
                this::issue);
    }

    public AuthorityDecision<PunishmentState, PunishmentReceipt> handle(
            AuthorityCommand<IssuePunishment> command,
            AuthorityRecord<PunishmentState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<PunishmentState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, PunishmentState.empty());
    }

    public static AggregateId aggregateId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new AggregateId("punishment:" + subjectId.value());
    }

    public static String cacheKey(SubjectId subjectId) {
        return PunishmentContracts.CONTRACT.value() + ":" + aggregateId(subjectId).value();
    }

    private AuthorityMutationResult<PunishmentState, PunishmentReceipt> issue(
            AuthorityCommand<IssuePunishment> command,
            AuthorityRecord<PunishmentState> currentRecord) {
        IssuePunishment payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.subjectId()))) {
            throw new IllegalArgumentException("punishment aggregate must be keyed by Subject");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        ActivePunishmentSnapshot snapshot = new ActivePunishmentSnapshot(
                payload.subjectId(),
                payload.punishmentId(),
                payload.reason(),
                command.authenticatedPrincipal(),
                payload.issuedAt(),
                payload.expiresAt());
        PunishmentState state = new PunishmentState(snapshot);
        PunishmentReceipt receipt = PunishmentReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        PunishmentIssued event = new PunishmentIssued(snapshot, nextRevision);
        String aggregateKey = aggregateId(snapshot.subjectId()).value();
        String statePayload = state.wireValue(nextRevision);
        String projectionKey = PunishmentContracts.ACTIVE_PROJECTION + ":" + snapshot.subjectId().value();
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, projectionKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.subjectId()), statePayload)));
    }

    private static PunishmentReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return PunishmentReceipt.rejected(reason.name());
    }
}
