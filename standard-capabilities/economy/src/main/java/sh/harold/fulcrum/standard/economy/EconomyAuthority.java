package sh.harold.fulcrum.standard.economy;

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
import sh.harold.fulcrum.standard.contracts.EconomyContracts;

import java.util.List;
import java.util.Objects;

public final class EconomyAuthority {
    private final AuthorityCommandProcessor<EconomyState, PostLedgerEntry, EconomyReceipt> processor;

    public EconomyAuthority(IdempotencyLedger<EconomyState, EconomyReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                EconomyAuthority::rejectionReceipt,
                this::post);
    }

    public AuthorityDecision<EconomyState, EconomyReceipt> handle(
            AuthorityCommand<PostLedgerEntry> command,
            AuthorityRecord<EconomyState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<EconomyState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, EconomyState.empty());
    }

    public static EconomyAccountId accountId(SubjectId subjectId, String currencyKey) {
        return new EconomyAccountId(subjectId, currencyKey);
    }

    public static AggregateId aggregateId(EconomyAccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return new AggregateId("economy:" + accountId.value());
    }

    public static String cacheKey(EconomyAccountId accountId) {
        return EconomyContracts.CONTRACT.value() + ":" + aggregateId(accountId).value();
    }

    private AuthorityMutationResult<EconomyState, EconomyReceipt> post(
            AuthorityCommand<PostLedgerEntry> command,
            AuthorityRecord<EconomyState> currentRecord) {
        PostLedgerEntry payload = command.envelope().payload();
        EconomyAccountId accountId = payload.accountId();
        if (!command.envelope().aggregateId().equals(aggregateId(accountId))) {
            throw new IllegalArgumentException("economy aggregate must be keyed by Subject and currency");
        }

        long nextBalance = Math.addExact(currentRecord.state().balanceMinorUnits(), payload.deltaMinorUnits());
        if (nextBalance < 0) {
            throw new IllegalArgumentException("economy ledger entry cannot make balance negative");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        String entryId = command.envelope().idempotencyKey().value();
        EconomyLedgerEntry ledgerEntry = new EconomyLedgerEntry(
                entryId,
                accountId,
                payload.deltaMinorUnits(),
                nextBalance,
                payload.reason(),
                command.authenticatedPrincipal(),
                payload.occurredAt(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value(),
                nextRevision);
        EconomyBalanceSnapshot snapshot = new EconomyBalanceSnapshot(
                accountId,
                nextBalance,
                ledgerEntry.entryId(),
                command.authenticatedPrincipal(),
                payload.occurredAt());
        EconomyState state = currentRecord.state().append(ledgerEntry, snapshot);
        EconomyReceipt receipt = EconomyReceipt.accepted(
                ledgerEntry,
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        EconomyLedgerEntryRecorded event = new EconomyLedgerEntryRecorded(ledgerEntry, nextRevision);
        String aggregateKey = aggregateId(accountId).value();
        String statePayload = state.wireValue(nextRevision);
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, EconomyContracts.BALANCE_PROJECTION + ":" + accountId.value(), snapshot.wireValue(nextRevision)),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, EconomyContracts.LEDGER_PROJECTION + ":" + ledgerEntry.entryId(), ledgerEntry.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(accountId), snapshot.wireValue(nextRevision))));
    }

    private static EconomyReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return EconomyReceipt.rejected(reason.name());
    }
}
