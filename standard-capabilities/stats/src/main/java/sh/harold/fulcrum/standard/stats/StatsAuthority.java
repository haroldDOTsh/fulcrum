package sh.harold.fulcrum.standard.stats;

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
import sh.harold.fulcrum.standard.contracts.StatsContracts;

import java.util.List;
import java.util.Objects;

public final class StatsAuthority {
    private final AuthorityCommandProcessor<StatsState, RecordStatDelta, StatsReceipt> processor;

    public StatsAuthority(IdempotencyLedger<StatsState, StatsReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                StatsAuthority::rejectionReceipt,
                this::record);
    }

    public AuthorityDecision<StatsState, StatsReceipt> handle(
            AuthorityCommand<RecordStatDelta> command,
            AuthorityRecord<StatsState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<StatsState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, StatsState.empty());
    }

    public static StatsCounterId counterId(SubjectId subjectId, String statKey) {
        return new StatsCounterId(subjectId, statKey);
    }

    public static AggregateId aggregateId(StatsCounterId counterId) {
        Objects.requireNonNull(counterId, "counterId");
        return new AggregateId("stats:" + counterId.value());
    }

    public static String cacheKey(StatsCounterId counterId) {
        return StatsContracts.CONTRACT.value() + ":" + aggregateId(counterId).value();
    }

    private AuthorityMutationResult<StatsState, StatsReceipt> record(
            AuthorityCommand<RecordStatDelta> command,
            AuthorityRecord<StatsState> currentRecord) {
        RecordStatDelta payload = command.envelope().payload();
        StatsCounterId counterId = payload.counterId();
        if (!command.envelope().aggregateId().equals(aggregateId(counterId))) {
            throw new IllegalArgumentException("stats aggregate must be keyed by Subject and stat key");
        }

        long nextTotal = Math.addExact(currentRecord.state().total(), payload.delta());
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        String entryId = command.envelope().idempotencyKey().value();
        StatsLedgerEntry ledgerEntry = new StatsLedgerEntry(
                entryId,
                counterId,
                payload.experienceId(),
                payload.delta(),
                nextTotal,
                command.authenticatedPrincipal(),
                payload.occurredAt(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value(),
                nextRevision);
        StatsCounterSnapshot snapshot = new StatsCounterSnapshot(
                counterId,
                nextTotal,
                ledgerEntry.entryId(),
                command.authenticatedPrincipal(),
                payload.occurredAt());
        StatsState state = currentRecord.state().append(ledgerEntry, snapshot);
        StatsReceipt receipt = StatsReceipt.accepted(
                ledgerEntry,
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        StatsDeltaRecorded event = new StatsDeltaRecorded(ledgerEntry, nextRevision);
        String aggregateKey = aggregateId(counterId).value();
        String statePayload = state.wireValue(nextRevision);
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, StatsContracts.COUNTER_PROJECTION + ":" + counterId.value(), snapshot.wireValue(nextRevision)),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, StatsContracts.EXPERIENCE_COUNTER_PROJECTION + ":" + payload.experienceCounterId().value(), ledgerEntry.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, StatsContracts.LEDGER_PROJECTION + ":" + ledgerEntry.entryId(), ledgerEntry.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(counterId), snapshot.wireValue(nextRevision))));
    }

    private static StatsReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return StatsReceipt.rejected(reason.name());
    }
}
