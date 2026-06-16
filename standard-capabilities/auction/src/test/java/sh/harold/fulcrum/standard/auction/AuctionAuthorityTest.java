package sh.harold.fulcrum.standard.auction;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.standard.contracts.AuctionContracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-17T01:00:00Z");
    private static final AuctionId AUCTION_ID = new AuctionId("auction-suite-1");
    private static final SubjectId SELLER = subject("00000000-0000-0000-0000-000000001501");
    private static final SubjectId FIRST_BIDDER = subject("00000000-0000-0000-0000-000000001502");
    private static final SubjectId SECOND_BIDDER = subject("00000000-0000-0000-0000-000000001503");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-auction-client");
    private static final String CURRENCY = "coins";

    @Test
    void placeBidHoldsEscrowAndCompensatesPreviousBidderWithAudit() {
        AuctionAuthority authority = new AuctionAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<AuctionState, AuctionReceipt> opened = authority.handle(
                command("command-auction-open", "auction-idem-open", open(0), PRINCIPAL, PRINCIPAL, 5, "payload-open"),
                AuctionAuthority.emptyRecord(5));
        AuthorityDecision<AuctionState, AuctionReceipt> firstBid = authority.handle(
                command("command-auction-bid-1", "auction-idem-bid-1", bid(FIRST_BIDDER, 100, 1), PRINCIPAL, PRINCIPAL, 5, "payload-bid-1"),
                record(opened));
        AuthorityDecision<AuctionState, AuctionReceipt> secondBid = authority.handle(
                command("command-auction-bid-2", "auction-idem-bid-2", bid(SECOND_BIDDER, 150, 2), PRINCIPAL, PRINCIPAL, 5, "payload-bid-2"),
                record(firstBid));

        assertEquals(AuthorityDecisionStatus.ACCEPTED, secondBid.status());
        assertEquals(new Revision(3), secondBid.revision());
        assertEquals(Optional.of(SECOND_BIDDER), secondBid.state().current().orElseThrow().highestBidderSubjectId());
        assertEquals(List.of(AuctionEscrowAction.RELEASE, AuctionEscrowAction.HOLD),
                secondBid.response().escrowEntries().stream().map(AuctionEscrowEntry::action).toList());
        assertEquals(List.of(
                        AuthorityEmissionKind.EVENT,
                        AuthorityEmissionKind.STATE,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.PROJECTION,
                        AuthorityEmissionKind.RESPONSE,
                        AuthorityEmissionKind.CACHE_WRITE),
                secondBid.emissions().stream().map(emission -> emission.kind()).toList());

        AuctionProjection projection = AuctionProjection.rebuild(List.of(event(opened), event(firstBid), event(secondBid)));
        assertEquals(0, projection.escrowedAmount(AUCTION_ID, FIRST_BIDDER, CURRENCY));
        assertEquals(150, projection.escrowedAmount(AUCTION_ID, SECOND_BIDDER, CURRENCY));
        assertEquals(List.of("OPENED", "BID_ACCEPTED", "BID_ACCEPTED"),
                projection.auditEntries().stream().map(AuctionAuditEntry::action).toList());
    }

    @Test
    void duplicateBidReplaysStoredAuctionDecisionWithoutSecondAuditEntry() {
        AuctionAuthority authority = new AuctionAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityDecision<AuctionState, AuctionReceipt> opened = authority.handle(
                command("command-auction-open-2", "auction-idem-open-2", open(0), PRINCIPAL, PRINCIPAL, 5, "payload-open-2"),
                AuctionAuthority.emptyRecord(5));
        AuthorityCommand<AuctionCommand> bid = command(
                "command-auction-bid-duplicate",
                "auction-idem-bid-duplicate",
                bid(FIRST_BIDDER, 100, 1),
                PRINCIPAL,
                PRINCIPAL,
                5,
                "payload-bid-duplicate");

        AuthorityDecision<AuctionState, AuctionReceipt> first = authority.handle(bid, record(opened));
        AuthorityDecision<AuctionState, AuctionReceipt> replay = authority.handle(bid, record(first));

        assertEquals(first.response(), replay.response());
        assertEquals(first.state(), replay.state());
        assertEquals(2, replay.state().auditEntries().size());
        assertTrue(replay.replayed());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejectedBeforeAuctionMutation() {
        AuctionAuthority authority = new AuctionAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityDecision<AuctionState, AuctionReceipt> opened = authority.handle(
                command("command-auction-open-3", "auction-idem-open-3", open(0), PRINCIPAL, PRINCIPAL, 5, "payload-open-3"),
                AuctionAuthority.emptyRecord(5));
        AuthorityCommand<AuctionCommand> firstCommand = command(
                "command-auction-bid-3a",
                "auction-idem-bid-3",
                bid(FIRST_BIDDER, 100, 1),
                PRINCIPAL,
                PRINCIPAL,
                5,
                "payload-bid-3a");
        AuthorityDecision<AuctionState, AuctionReceipt> first = authority.handle(firstCommand, record(opened));

        AuthorityDecision<AuctionState, AuctionReceipt> conflict = authority.handle(
                command(
                        "command-auction-bid-3b",
                        "auction-idem-bid-3",
                        bid(SECOND_BIDDER, 150, 2),
                        PRINCIPAL,
                        PRINCIPAL,
                        5,
                        "payload-bid-3b"),
                record(first));

        assertRejected(conflict, AuthorityRejectionReason.IDEMPOTENCY_CONFLICT);
        assertEquals(first.state(), conflict.state());
    }

    @Test
    void revisionMismatchRejectsBeforeAuctionMutation() {
        AuctionAuthority authority = new AuctionAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<AuctionState, AuctionReceipt> decision = authority.handle(
                command("command-auction-revision", "auction-idem-revision", open(9), PRINCIPAL, PRINCIPAL, 5, "payload-revision"),
                AuctionAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.REVISION_MISMATCH);
        assertEquals(AuctionState.empty(), decision.state());
    }

    @Test
    void principalMismatchRejectsBeforeAuctionMutation() {
        AuctionAuthority authority = new AuctionAuthority(new InMemoryIdempotencyLedger<>());

        AuthorityDecision<AuctionState, AuctionReceipt> decision = authority.handle(
                command(
                        "command-auction-principal",
                        "auction-idem-principal",
                        open(0),
                        PRINCIPAL,
                        new PrincipalId("transport-attacker"),
                        5,
                        "payload-principal"),
                AuctionAuthority.emptyRecord(5));

        assertRejected(decision, AuthorityRejectionReason.PRINCIPAL_MISMATCH);
    }

    @Test
    void aggregateMustBeKeyedByAuction() {
        AuctionAuthority authority = new AuctionAuthority(new InMemoryIdempotencyLedger<>());
        AuthorityCommand<AuctionCommand> command = command(
                "command-auction-aggregate",
                "auction-idem-aggregate",
                open(0),
                PRINCIPAL,
                PRINCIPAL,
                5,
                "payload-aggregate",
                new AggregateId("auction:wrong-auction"));

        assertThrows(IllegalArgumentException.class, () -> authority.handle(command, AuctionAuthority.emptyRecord(5)));
    }

    private static AuthorityRecord<AuctionState> record(AuthorityDecision<AuctionState, AuctionReceipt> decision) {
        return new AuthorityRecord<>(decision.revision(), 5, decision.state());
    }

    private static AuctionEventRecorded event(AuthorityDecision<AuctionState, AuctionReceipt> decision) {
        return new AuctionEventRecorded(
                decision.response().snapshot().orElseThrow(),
                decision.response().escrowEntries(),
                decision.response().auditEntry().orElseThrow(),
                decision.revision());
    }

    private static OpenAuction open(long expectedRevision) {
        return new OpenAuction(AUCTION_ID, SELLER, "item:diamond-sword", CURRENCY, NOW, expectedRevision);
    }

    private static PlaceAuctionBid bid(SubjectId bidder, long amount, long expectedRevision) {
        return new PlaceAuctionBid(AUCTION_ID, bidder, amount, CURRENCY, NOW.plusSeconds(expectedRevision), expectedRevision);
    }

    private static AuthorityCommand<AuctionCommand> command(
            String commandId,
            String idempotencyKey,
            AuctionCommand payload,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            String payloadFingerprint) {
        return command(
                commandId,
                idempotencyKey,
                payload,
                declaredPrincipal,
                authenticatedPrincipal,
                fencingEpoch,
                payloadFingerprint,
                AuctionAuthority.aggregateId(payload.auctionId()));
    }

    private static AuthorityCommand<AuctionCommand> command(
            String commandId,
            String idempotencyKey,
            AuctionCommand payload,
            PrincipalId declaredPrincipal,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch,
            String payloadFingerprint,
            AggregateId aggregateId) {
        CommandEnvelope<AuctionCommand> envelope = new CommandEnvelope<>(
                new CommandId(commandId),
                new IdempotencyKey(idempotencyKey),
                declaredPrincipal,
                aggregateId,
                AuctionContracts.CONTRACT,
                new CommandName(payload instanceof OpenAuction ? "open-auction" : "place-auction-bid"),
                new TraceEnvelope(
                        "trace-auction-1",
                        "span-auction-1",
                        Optional.empty(),
                        NOW,
                        "standard-auction-test",
                        new InstanceId("instance-auction-test")),
                Optional.empty(),
                payload);
        return new AuthorityCommand<>(
                envelope,
                authenticatedPrincipal,
                fencingEpoch,
                Optional.of(new Revision(payload.expectedRevision())),
                payloadFingerprint,
                NOW);
    }

    private static void assertRejected(
            AuthorityDecision<AuctionState, AuctionReceipt> decision,
            AuthorityRejectionReason reason) {
        assertEquals(AuthorityDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(reason), decision.rejectionReason());
        assertFalse(decision.replayed());
        assertTrue(decision.response().rejectionReason().orElseThrow().contains(reason.name()));
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }
}
