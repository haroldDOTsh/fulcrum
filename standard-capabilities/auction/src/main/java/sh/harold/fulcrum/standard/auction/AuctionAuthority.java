package sh.harold.fulcrum.standard.auction;

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
import sh.harold.fulcrum.standard.contracts.AuctionContracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AuctionAuthority {
    private final AuthorityCommandProcessor<AuctionState, AuctionCommand, AuctionReceipt> processor;

    public AuctionAuthority(IdempotencyLedger<AuctionState, AuctionReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                AuctionAuthority::rejectionReceipt,
                this::apply);
    }

    public AuthorityDecision<AuctionState, AuctionReceipt> handle(
            AuthorityCommand<AuctionCommand> command,
            AuthorityRecord<AuctionState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<AuctionState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, AuctionState.empty());
    }

    public static AggregateId aggregateId(AuctionId auctionId) {
        Objects.requireNonNull(auctionId, "auctionId");
        return new AggregateId("auction:" + auctionId.value());
    }

    public static String cacheKey(AuctionId auctionId) {
        return AuctionContracts.CONTRACT.value() + ":" + aggregateId(auctionId).value();
    }

    private AuthorityMutationResult<AuctionState, AuctionReceipt> apply(
            AuthorityCommand<AuctionCommand> command,
            AuthorityRecord<AuctionState> currentRecord) {
        AuctionCommand payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.auctionId()))) {
            throw new IllegalArgumentException("auction aggregate must be keyed by Auction");
        }
        if (payload instanceof OpenAuction openAuction) {
            return open(command, currentRecord, openAuction);
        }
        if (payload instanceof PlaceAuctionBid placeAuctionBid) {
            return placeBid(command, currentRecord, placeAuctionBid);
        }
        throw new IllegalArgumentException("unsupported auction command");
    }

    private AuthorityMutationResult<AuctionState, AuctionReceipt> open(
            AuthorityCommand<AuctionCommand> command,
            AuthorityRecord<AuctionState> currentRecord,
            OpenAuction payload) {
        if (currentRecord.state().current().isPresent()) {
            throw new IllegalArgumentException("auction already exists for aggregate");
        }
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        AuctionSnapshot snapshot = AuctionSnapshot.open(payload, command.authenticatedPrincipal());
        AuctionAuditEntry auditEntry = auditEntry(
                command,
                payload.auctionId(),
                "OPENED",
                Optional.of(payload.sellerSubjectId()),
                0,
                payload.currencyKey(),
                payload.openedAt(),
                nextRevision);
        return accepted(command, currentRecord, snapshot, List.of(), auditEntry, nextRevision);
    }

    private AuthorityMutationResult<AuctionState, AuctionReceipt> placeBid(
            AuthorityCommand<AuctionCommand> command,
            AuthorityRecord<AuctionState> currentRecord,
            PlaceAuctionBid payload) {
        AuctionSnapshot current = currentRecord.state().current()
                .orElseThrow(() -> new IllegalArgumentException("auction must be open before bids"));
        AuctionSnapshot snapshot = current.withBid(payload, command.authenticatedPrincipal());
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        ArrayList<AuctionEscrowEntry> escrowEntries = new ArrayList<>();
        current.highestBidderSubjectId().ifPresent(previousBidder -> escrowEntries.add(escrowEntry(
                command,
                payload.auctionId(),
                previousBidder,
                AuctionEscrowAction.RELEASE,
                current.highestBidMinorUnits(),
                current.currencyKey(),
                payload.placedAt(),
                nextRevision,
                "release")));
        escrowEntries.add(escrowEntry(
                command,
                payload.auctionId(),
                payload.bidderSubjectId(),
                AuctionEscrowAction.HOLD,
                payload.bidMinorUnits(),
                payload.currencyKey(),
                payload.placedAt(),
                nextRevision,
                "hold"));
        AuctionAuditEntry auditEntry = auditEntry(
                command,
                payload.auctionId(),
                "BID_ACCEPTED",
                Optional.of(payload.bidderSubjectId()),
                payload.bidMinorUnits(),
                payload.currencyKey(),
                payload.placedAt(),
                nextRevision);
        return accepted(command, currentRecord, snapshot, List.copyOf(escrowEntries), auditEntry, nextRevision);
    }

    private AuthorityMutationResult<AuctionState, AuctionReceipt> accepted(
            AuthorityCommand<AuctionCommand> command,
            AuthorityRecord<AuctionState> currentRecord,
            AuctionSnapshot snapshot,
            List<AuctionEscrowEntry> escrowEntries,
            AuctionAuditEntry auditEntry,
            Revision nextRevision) {
        AuctionState state = currentRecord.state().append(snapshot, escrowEntries, auditEntry);
        AuctionReceipt receipt = AuctionReceipt.accepted(
                snapshot,
                escrowEntries,
                auditEntry,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        AuctionEventRecorded event = new AuctionEventRecorded(snapshot, escrowEntries, auditEntry, nextRevision);
        String aggregateKey = aggregateId(snapshot.auctionId()).value();
        String statePayload = state.wireValue(nextRevision);
        ArrayList<AuthorityEmission> emissions = new ArrayList<>();
        emissions.add(new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()));
        emissions.add(new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload));
        emissions.add(new AuthorityEmission(AuthorityEmissionKind.PROJECTION, AuctionContracts.LISTING_PROJECTION + ":" + snapshot.auctionId().value(), snapshot.wireValue(nextRevision)));
        escrowEntries.forEach(escrowEntry -> emissions.add(new AuthorityEmission(
                AuthorityEmissionKind.PROJECTION,
                AuctionContracts.ESCROW_PROJECTION + ":" + escrowEntry.accountId().value(),
                escrowEntry.wireValue())));
        emissions.add(new AuthorityEmission(AuthorityEmissionKind.PROJECTION, AuctionContracts.AUDIT_PROJECTION + ":" + auditEntry.auditEntryId(), auditEntry.wireValue()));
        emissions.add(new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()));
        emissions.add(new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.auctionId()), snapshot.wireValue(nextRevision)));
        return new AuthorityMutationResult<>(nextRevision, state, receipt, List.copyOf(emissions));
    }

    private static AuctionEscrowEntry escrowEntry(
            AuthorityCommand<AuctionCommand> command,
            AuctionId auctionId,
            sh.harold.fulcrum.api.kernel.SubjectId subjectId,
            AuctionEscrowAction action,
            long amountMinorUnits,
            String currencyKey,
            java.time.Instant recordedAt,
            Revision revision,
            String suffix) {
        return new AuctionEscrowEntry(
                command.envelope().idempotencyKey().value() + ":" + suffix,
                auctionId,
                subjectId,
                action,
                amountMinorUnits,
                currencyKey,
                command.authenticatedPrincipal(),
                recordedAt,
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value(),
                revision);
    }

    private static AuctionAuditEntry auditEntry(
            AuthorityCommand<AuctionCommand> command,
            AuctionId auctionId,
            String action,
            Optional<sh.harold.fulcrum.api.kernel.SubjectId> actorSubjectId,
            long amountMinorUnits,
            String currencyKey,
            java.time.Instant recordedAt,
            Revision revision) {
        return new AuctionAuditEntry(
                command.envelope().idempotencyKey().value() + ":audit",
                auctionId,
                action,
                actorSubjectId,
                amountMinorUnits,
                currencyKey,
                command.authenticatedPrincipal(),
                recordedAt,
                revision);
    }

    private static AuctionReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return AuctionReceipt.rejected(reason.name());
    }
}
