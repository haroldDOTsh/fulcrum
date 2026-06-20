package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.EventName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.data.contract.CommandDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.EventDeclaration;
import sh.harold.fulcrum.data.contract.FieldDeclaration;
import sh.harold.fulcrum.data.contract.FieldType;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;
import sh.harold.fulcrum.data.contract.TopicFamily;

import java.util.List;
import java.util.Optional;

public final class AuctionEscrowContract {
    public static final ContractName CONTRACT = new ContractName("auction.escrow.v1");
    public static final String AUTHORITY_DOMAIN = "auction-escrow";
    public static final String RESOURCE_CLASS = "external-authority";
    public static final CommandName OPEN = new CommandName("auction.escrow.open");
    public static final CommandName HOLD = new CommandName("auction.escrow.hold");
    public static final CommandName SETTLE = new CommandName("auction.escrow.settle");
    public static final CommandName CANCEL = new CommandName("auction.escrow.cancel");

    private AuctionEscrowContract() {
    }

    public static AggregateId aggregateId(String auctionId) {
        return new AggregateId("escrow:" + EscrowNames.requireNonBlank(auctionId, "auctionId"));
    }

    public static String projectionKey(String auctionId) {
        return CONTRACT.value() + ":" + aggregateId(auctionId).value();
    }

    public static CommandName commandName(AuctionEscrowCommand payload) {
        if (payload instanceof OpenEscrow) {
            return OPEN;
        }
        if (payload instanceof PlaceHold) {
            return HOLD;
        }
        if (payload instanceof SettleEscrow) {
            return SETTLE;
        }
        if (payload instanceof CancelEscrow) {
            return CANCEL;
        }
        throw new IllegalArgumentException("unknown escrow command");
    }

    public static String payloadFingerprint(AuctionEscrowCommand payload, String idempotencyKey) {
        return payload.getClass().getSimpleName()
                + ":" + EscrowNames.requireNonBlank(payload.auctionId(), "auctionId")
                + ":" + EscrowNames.requireNonBlank(idempotencyKey, "idempotencyKey");
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                new CapabilityId("auction-escrow-backend"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(contract()),
                List.of(new CapabilityAuthorityDeclaration(AUTHORITY_DOMAIN, RESOURCE_CLASS, 1)),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }

    private static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(
                        new CommandDeclaration(OPEN, "OpenEscrow", List.of(), false),
                        new CommandDeclaration(HOLD, "PlaceHold", List.of(), false),
                        new CommandDeclaration(SETTLE, "SettleEscrow", List.of(), false),
                        new CommandDeclaration(CANCEL, "CancelEscrow", List.of(), false)),
                List.of(
                        new EventDeclaration(new EventName("auction.escrow.opened"), "EscrowOpened", List.of()),
                        new EventDeclaration(new EventName("auction.escrow.held"), "HoldPlaced", List.of()),
                        new EventDeclaration(new EventName("auction.escrow.settled"), "EscrowSettled", List.of()),
                        new EventDeclaration(new EventName("auction.escrow.refunded"), "EscrowRefunded", List.of())),
                Optional.empty(),
                List.of(new ProjectionDeclaration(
                        "AuctionEscrowState",
                        "auction_escrow_state",
                        List.of(
                                new FieldDeclaration("auction_id", FieldType.STRING, false),
                                new FieldDeclaration("status", FieldType.STRING, false),
                                new FieldDeclaration("total_held_minor", FieldType.LONG, false)))),
                List.of(
                        new TopicDeclaration("cmd.auction.escrow", TopicFamily.COMMAND),
                        new TopicDeclaration("evt.auction.escrow", TopicFamily.EVENT)));
    }
}
