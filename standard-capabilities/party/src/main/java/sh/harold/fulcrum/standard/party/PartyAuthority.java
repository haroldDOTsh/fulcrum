package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.standard.contracts.PartyContracts;

import java.util.List;
import java.util.Objects;

public final class PartyAuthority {
    private final AuthorityCommandProcessor<PartyState, FormParty, PartyReceipt> processor;

    public PartyAuthority(IdempotencyLedger<PartyState, PartyReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                PartyAuthority::rejectionReceipt,
                this::form);
    }

    public AuthorityDecision<PartyState, PartyReceipt> handle(
            AuthorityCommand<FormParty> command,
            AuthorityRecord<PartyState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<PartyState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, PartyState.empty());
    }

    public static AggregateId aggregateId(PartyId partyId) {
        Objects.requireNonNull(partyId, "partyId");
        return new AggregateId("party:" + partyId.value());
    }

    public static String cacheKey(PartyId partyId) {
        return PartyContracts.CONTRACT.value() + ":" + aggregateId(partyId).value();
    }

    private AuthorityMutationResult<PartyState, PartyReceipt> form(
            AuthorityCommand<FormParty> command,
            AuthorityRecord<PartyState> currentRecord) {
        FormParty payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.partyId()))) {
            throw new IllegalArgumentException("party aggregate must be keyed by Party");
        }
        if (currentRecord.state().current().isPresent()) {
            throw new IllegalArgumentException("party already exists for aggregate");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        PartyRosterSnapshot snapshot = new PartyRosterSnapshot(
                payload.partyId(),
                payload.leaderSubjectId(),
                payload.memberSubjectIds(),
                command.authenticatedPrincipal(),
                payload.formedAt());
        PartyState state = new PartyState(snapshot);
        PartyReceipt receipt = PartyReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        PartyFormed event = new PartyFormed(snapshot, nextRevision);
        String aggregateKey = aggregateId(snapshot.partyId()).value();
        String statePayload = state.wireValue(nextRevision);
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, PartyContracts.ROSTER_PROJECTION + ":" + snapshot.partyId().value(), statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.partyId()), statePayload)));
    }

    private static PartyReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return PartyReceipt.rejected(reason.name());
    }
}
