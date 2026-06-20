package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;

import java.util.List;
import java.util.Objects;

public final class AuctionEscrowAuthority {
    public static final ContractName CONTRACT = AuctionEscrowContract.CONTRACT;
    public static final String AUTHORITY_DOMAIN = AuctionEscrowContract.AUTHORITY_DOMAIN;
    public static final String RESOURCE_CLASS = AuctionEscrowContract.RESOURCE_CLASS;

    private final AuthorityCommandProcessor<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> processor;

    public AuctionEscrowAuthority(IdempotencyLedger<AuctionEscrowState, AuctionEscrowReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                reason -> AuctionEscrowReceipt.rejected(reason.name()),
                this::apply);
    }

    public AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> handle(
            AuthorityCommand<AuctionEscrowCommand> command,
            AuthorityRecord<AuctionEscrowState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<AuctionEscrowState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, AuctionEscrowState.empty());
    }

    public static AggregateId aggregateId(String auctionId) {
        return AuctionEscrowContract.aggregateId(auctionId);
    }

    public static String projectionKey(String auctionId) {
        return AuctionEscrowContract.projectionKey(auctionId);
    }

    public static CapabilityDescriptor descriptor() {
        return AuctionEscrowContract.descriptor();
    }

    private AuthorityMutationResult<AuctionEscrowState, AuctionEscrowReceipt> apply(
            AuthorityCommand<AuctionEscrowCommand> command,
            AuthorityRecord<AuctionEscrowState> currentRecord) {
        AuctionEscrowCommand payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.auctionId()))) {
            throw new IllegalArgumentException("escrow aggregate must be keyed by auction");
        }
        if (!command.envelope().contractName().equals(CONTRACT)) {
            throw new IllegalArgumentException("escrow command must use auction.escrow.v1");
        }

        EscrowSnapshot snapshot;
        if (payload instanceof OpenEscrow open) {
            if (currentRecord.state().current().isPresent()) {
                throw new IllegalStateException("escrow already opened");
            }
            snapshot = EscrowSnapshot.open(open);
        } else if (payload instanceof PlaceHold hold) {
            snapshot = liveCurrent(currentRecord).withHold(hold);
        } else if (payload instanceof SettleEscrow settle) {
            snapshot = liveCurrent(currentRecord).settle(settle);
        } else if (payload instanceof CancelEscrow cancel) {
            snapshot = liveCurrent(currentRecord).cancel(cancel);
        } else {
            throw new IllegalArgumentException("unknown escrow command");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        AuctionEscrowState state = currentRecord.state().with(snapshot);
        AuctionEscrowReceipt receipt = AuctionEscrowReceipt.accepted(snapshot, nextRevision, command.fencingEpoch());
        String aggregateKey = aggregateId(snapshot.auctionId()).value();
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, eventWireValue(command, snapshot, nextRevision)),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, state.wireValue(nextRevision.value())),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, projectionKey(snapshot.auctionId()), state.wireValue(nextRevision.value()))));
    }

    private static EscrowSnapshot liveCurrent(AuthorityRecord<AuctionEscrowState> currentRecord) {
        return currentRecord.state().current()
                .orElseThrow(() -> new IllegalStateException("escrow must be opened before mutation commands"));
    }

    private static String eventWireValue(
            AuthorityCommand<AuctionEscrowCommand> command,
            EscrowSnapshot snapshot,
            Revision revision) {
        return "contract=" + AuctionEscrowContract.CONTRACT.value()
                + "|command=" + command.envelope().commandName().value()
                + "|auctionId=" + snapshot.auctionId()
                + "|status=" + snapshot.status()
                + "|revision=" + revision.value()
                + "|updatedAt=" + snapshot.updatedAt()
                + "|releasePlan=" + snapshot.releasePlan().map(ReleasePlan::wireValue).orElse("none");
    }
}
