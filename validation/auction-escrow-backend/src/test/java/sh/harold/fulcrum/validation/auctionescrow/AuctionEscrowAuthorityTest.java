package sh.harold.fulcrum.validation.auctionescrow;

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
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionEscrowAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("auction-escrow-backend");
    private static final String AUCTION = "auction-alpha";

    @Test
    void settlesWinnerPayoutAndLosingRefundsWithConservation() {
        AuctionEscrowAuthority authority = authority();
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> opened = authority.handle(
                command("command-open", "idem-open", new OpenEscrow(AUCTION, "seller", "diamond-sword", "COIN", NOW), Optional.of(new Revision(0))),
                AuctionEscrowAuthority.emptyRecord(3));
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> firstHold = authority.handle(
                command("command-hold-1", "idem-hold-1", new PlaceHold(AUCTION, "bidder-low", 100, "COIN", NOW.plusSeconds(1)), Optional.of(new Revision(1))),
                record(opened));
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> secondHold = authority.handle(
                command("command-hold-2", "idem-hold-2", new PlaceHold(AUCTION, "bidder-high", 150, "COIN", NOW.plusSeconds(2)), Optional.of(new Revision(2))),
                record(firstHold));

        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> settled = authority.handle(
                command("command-settle", "idem-settle", new SettleEscrow(AUCTION, NOW.plusSeconds(3)), Optional.of(new Revision(3))),
                record(secondHold));

        ReleasePlan plan = settled.state().current().orElseThrow().releasePlan().orElseThrow();
        assertEquals(AuthorityDecisionStatus.ACCEPTED, settled.status());
        assertEquals(EscrowStatus.SETTLED, settled.state().current().orElseThrow().status());
        assertEquals(250, plan.totalHeldMinor());
        assertEquals(150, plan.totalPayoutMinor());
        assertEquals(100, plan.totalRefundedMinor());
        assertEquals(250, settled.response().totalReleasedMinor().orElseThrow());
        assertEquals(ReleaseLineKind.LOSER_REFUND, plan.lines().getFirst().kind());
        assertEquals(ReleaseLineKind.WINNER_PAYOUT, plan.lines().get(1).kind());
        assertEquals("seller", plan.lines().get(1).recipientId());
        assertTrue(plan.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void cancelRefundsAllHeldAmountsThroughSameReleasePlanShape() {
        AuctionEscrowAuthority authority = authority();
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> opened = authority.handle(
                command("command-open-cancel", "idem-open-cancel", new OpenEscrow(AUCTION, "seller", "elytra", "COIN", NOW), Optional.of(new Revision(0))),
                AuctionEscrowAuthority.emptyRecord(3));
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> hold = authority.handle(
                command("command-hold-cancel", "idem-hold-cancel", new PlaceHold(AUCTION, "bidder", 90, "COIN", NOW.plusSeconds(1)), Optional.of(new Revision(1))),
                record(opened));

        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> cancelled = authority.handle(
                command("command-cancel", "idem-cancel", new CancelEscrow(AUCTION, "seller-cancelled", NOW.plusSeconds(2)), Optional.of(new Revision(2))),
                record(hold));

        ReleasePlan plan = cancelled.state().current().orElseThrow().releasePlan().orElseThrow();
        assertEquals(EscrowStatus.CANCELLED, cancelled.state().current().orElseThrow().status());
        assertEquals(0, plan.totalPayoutMinor());
        assertEquals(90, plan.totalRefundedMinor());
        assertEquals(ReleaseLineKind.CANCEL_REFUND, plan.lines().getFirst().kind());
        assertEquals("bidder", plan.lines().getFirst().recipientId());
    }

    @Test
    void duplicateSettleReplaysStoredDecisionButNewTerminalCommandIsRejected() {
        AuctionEscrowAuthority authority = authority();
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> opened = authority.handle(
                command("command-open-replay", "idem-open-replay", new OpenEscrow(AUCTION, "seller", "trident", "COIN", NOW), Optional.of(new Revision(0))),
                AuctionEscrowAuthority.emptyRecord(3));
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> hold = authority.handle(
                command("command-hold-replay", "idem-hold-replay", new PlaceHold(AUCTION, "bidder", 200, "COIN", NOW.plusSeconds(1)), Optional.of(new Revision(1))),
                record(opened));
        AuthorityCommand<AuctionEscrowCommand> settle =
                command("command-settle-replay", "idem-settle-replay", new SettleEscrow(AUCTION, NOW.plusSeconds(2)), Optional.of(new Revision(2)));
        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> accepted = authority.handle(settle, record(hold));

        AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> replay = authority.handle(settle, record(accepted));

        assertTrue(replay.replayed());
        assertEquals(accepted.response(), replay.response());
        assertThrows(
                IllegalStateException.class,
                () -> authority.handle(
                        command("command-cancel-after-settle", "idem-cancel-after-settle", new CancelEscrow(AUCTION, "too-late", NOW.plusSeconds(3)), Optional.of(new Revision(3))),
                        record(accepted)));
    }

    @Test
    void escrowBackendSelfRegistersUnderAuthorityGrant() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        AuthorityBackendRegistrationReceipt receipt = controller.register(AuthorityBackendRegistrationRequest.credentialed(
                AuctionEscrowAuthority.descriptor(),
                securityContext(),
                "sha256:auction-escrow-backend",
                NOW));

        assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, receipt.status());
        assertEquals(Optional.of(PRINCIPAL), receipt.principalId());
        assertEquals(1, receipt.fencingEpoch());
    }

    private static AuctionEscrowAuthority authority() {
        return new AuctionEscrowAuthority(new InMemoryIdempotencyLedger<>());
    }

    private static AuthorityRecord<AuctionEscrowState> record(AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> decision) {
        return new AuthorityRecord<>(decision.revision(), 3, decision.state());
    }

    private static AuthorityCommand<AuctionEscrowCommand> command(
            String commandId,
            String idempotencyKey,
            AuctionEscrowCommand payload,
            Optional<Revision> expectedRevision) {
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL,
                        aggregateId(payload),
                        AuctionEscrowAuthority.CONTRACT,
                        commandName(payload),
                        trace(),
                        Optional.empty(),
                        payload),
                PRINCIPAL,
                3,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + idempotencyKey,
                NOW);
    }

    private static AggregateId aggregateId(AuctionEscrowCommand payload) {
        return AuctionEscrowAuthority.aggregateId(payload.auctionId());
    }

    private static CommandName commandName(AuctionEscrowCommand payload) {
        if (payload instanceof OpenEscrow) {
            return new CommandName("auction.escrow.open");
        }
        if (payload instanceof PlaceHold) {
            return new CommandName("auction.escrow.hold");
        }
        if (payload instanceof SettleEscrow) {
            return new CommandName("auction.escrow.settle");
        }
        return new CommandName("auction.escrow.cancel");
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-auction-escrow",
                "span-auction-escrow",
                Optional.empty(),
                NOW,
                "auction-escrow-test",
                new InstanceId("instance-auction-escrow-test"));
    }

    private static HostSecurityContext securityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-auction-escrow"),
                        "authority-backend",
                        new PoolId("pool-authority"),
                        new MachineRef("machine-authority"),
                        PRINCIPAL),
                "service-account:auction-escrow",
                new HostCredentialScope(Set.of(
                        AuthorityBackendGrants.authorityDomain(AuctionEscrowAuthority.AUTHORITY_DOMAIN),
                        AuthorityBackendGrants.resourceClass(AuctionEscrowAuthority.RESOURCE_CLASS))));
    }
}
