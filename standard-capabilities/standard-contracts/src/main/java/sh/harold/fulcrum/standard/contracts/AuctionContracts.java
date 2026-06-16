package sh.harold.fulcrum.standard.contracts;

import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.EventName;
import sh.harold.fulcrum.data.contract.AclRuleDeclaration;
import sh.harold.fulcrum.data.contract.CommandDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.EventDeclaration;
import sh.harold.fulcrum.data.contract.FieldDeclaration;
import sh.harold.fulcrum.data.contract.FieldType;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.SnapshotDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;
import sh.harold.fulcrum.data.contract.TopicFamily;

import java.util.List;
import java.util.Optional;

public final class AuctionContracts {
    public static final ContractName CONTRACT = new ContractName("standard.auction.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.auction";
    public static final String EVENT_TOPIC = "evt.standard.auction";
    public static final String STATE_TOPIC = "state.standard.auction";
    public static final String RESPONSE_TOPIC = "rsp.standard.auction";
    public static final String LISTING_PROJECTION = "standard_auction_listing";
    public static final String ESCROW_PROJECTION = "standard_auction_escrow";
    public static final String AUDIT_PROJECTION = "standard_auction_audit";

    private AuctionContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(
                        new CommandDeclaration(
                                new CommandName("open-auction"),
                                "OpenAuction",
                                List.of(
                                        new FieldDeclaration("auctionId", FieldType.STRING),
                                        new FieldDeclaration("sellerSubjectId", FieldType.STRING),
                                        new FieldDeclaration("itemRef", FieldType.STRING),
                                        new FieldDeclaration("currencyKey", FieldType.STRING),
                                        new FieldDeclaration("openedAt", FieldType.INSTANT),
                                        new FieldDeclaration("expectedRevision", FieldType.LONG)),
                                true),
                        new CommandDeclaration(
                                new CommandName("place-auction-bid"),
                                "PlaceAuctionBid",
                                List.of(
                                        new FieldDeclaration("auctionId", FieldType.STRING),
                                        new FieldDeclaration("bidderSubjectId", FieldType.STRING),
                                        new FieldDeclaration("bidMinorUnits", FieldType.LONG),
                                        new FieldDeclaration("currencyKey", FieldType.STRING),
                                        new FieldDeclaration("placedAt", FieldType.INSTANT),
                                        new FieldDeclaration("expectedRevision", FieldType.LONG)),
                                true)),
                List.of(new EventDeclaration(
                        new EventName("auction-event-recorded"),
                        "AuctionEventRecorded",
                        List.of(
                                new FieldDeclaration("auctionId", FieldType.STRING),
                                new FieldDeclaration("action", FieldType.STRING),
                                new FieldDeclaration("actorSubjectId", FieldType.STRING),
                                new FieldDeclaration("amountMinorUnits", FieldType.LONG),
                                new FieldDeclaration("currencyKey", FieldType.STRING),
                                new FieldDeclaration("recordedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "AuctionSnapshot",
                        List.of(
                                new FieldDeclaration("auctionId", FieldType.STRING),
                                new FieldDeclaration("sellerSubjectId", FieldType.STRING),
                                new FieldDeclaration("itemRef", FieldType.STRING),
                                new FieldDeclaration("currencyKey", FieldType.STRING),
                                new FieldDeclaration("highestBidderSubjectId", FieldType.STRING),
                                new FieldDeclaration("highestBidMinorUnits", FieldType.LONG),
                                new FieldDeclaration("status", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new ProjectionDeclaration(
                                "auctionListingProjection",
                                LISTING_PROJECTION,
                                List.of(
                                        new FieldDeclaration("auctionId", FieldType.STRING),
                                        new FieldDeclaration("sellerSubjectId", FieldType.STRING),
                                        new FieldDeclaration("itemRef", FieldType.STRING),
                                        new FieldDeclaration("currencyKey", FieldType.STRING),
                                        new FieldDeclaration("highestBidderSubjectId", FieldType.STRING),
                                        new FieldDeclaration("highestBidMinorUnits", FieldType.LONG),
                                        new FieldDeclaration("status", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "auctionEscrowProjection",
                                ESCROW_PROJECTION,
                                List.of(
                                        new FieldDeclaration("auctionId", FieldType.STRING),
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("currencyKey", FieldType.STRING),
                                        new FieldDeclaration("escrowedMinorUnits", FieldType.LONG),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "auctionAuditProjection",
                                AUDIT_PROJECTION,
                                List.of(
                                        new FieldDeclaration("auditEntryId", FieldType.STRING),
                                        new FieldDeclaration("auctionId", FieldType.STRING),
                                        new FieldDeclaration("action", FieldType.STRING),
                                        new FieldDeclaration("actorSubjectId", FieldType.STRING),
                                        new FieldDeclaration("amountMinorUnits", FieldType.LONG),
                                        new FieldDeclaration("currencyKey", FieldType.STRING),
                                        new FieldDeclaration("recordedAt", FieldType.INSTANT),
                                        new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-auction-client"), List.of("standard-auction-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-auction-authority"), List.of("standard-auction-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-auction-authority"), List.of("standard-auction-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-auction-authority"), List.of("standard-auction-client"))));
    }
}
