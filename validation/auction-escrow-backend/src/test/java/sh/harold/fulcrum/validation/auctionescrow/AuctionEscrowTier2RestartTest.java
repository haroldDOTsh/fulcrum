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
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRuntimeGuard;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionEscrowTier2RestartTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("auction-escrow-backend");
    private static final long FENCING_EPOCH = 11;

    @Test
    void resumesAfterWorkerReplacementAndKeepsReleaseAccountingSingleShot() {
        DurableEscrowStores stores = new DurableEscrowStores();
        stores.append(command("cmd-open", "idem-open", new OpenEscrow("auction-settle", "seller", "beacon", "COIN", NOW), Optional.of(new Revision(0))));
        stores.append(command("cmd-hold-low", "idem-hold-low", new PlaceHold("auction-settle", "low", 80, "COIN", NOW.plusSeconds(1)), Optional.of(new Revision(1))));
        stores.append(command("cmd-hold-high", "idem-hold-high", new PlaceHold("auction-settle", "high", 140, "COIN", NOW.plusSeconds(2)), Optional.of(new Revision(2))));
        AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> first =
                worker("worker-incarnation-1", stores);

        assertAccepted(first.handleNext());
        assertAccepted(first.handleNext());
        assertAccepted(first.handleNext());
        assertEquals(3, stores.committedWatermark());
        assertEquals("worker-incarnation-1", stores.decisions().getLast().workerIncarnation());

        stores.clearProjectionCache();
        stores.append(command("cmd-settle", "idem-settle", new SettleEscrow("auction-settle", NOW.plusSeconds(3)), Optional.of(new Revision(3))));
        stores.append(command("cmd-settle", "idem-settle", new SettleEscrow("auction-settle", NOW.plusSeconds(3)), Optional.of(new Revision(3))));
        stores.append(command("cmd-open-refund", "idem-open-refund", new OpenEscrow("auction-refund", "seller", "elytra", "COIN", NOW.plusSeconds(4)), Optional.of(new Revision(0))));
        stores.append(command("cmd-hold-refund", "idem-hold-refund", new PlaceHold("auction-refund", "bidder", 60, "COIN", NOW.plusSeconds(5)), Optional.of(new Revision(1))));
        stores.append(command("cmd-cancel-refund", "idem-cancel-refund", new CancelEscrow("auction-refund", "seller-cancelled", NOW.plusSeconds(6)), Optional.of(new Revision(2))));

        var second = AuthorityBackendRuntimeGuard.guard(admittedReceipt(), worker("worker-incarnation-2", stores));
        assertAccepted(second.handleNext());
        Optional<AuthorityRuntimeReceipt> replay = second.handleNext();
        assertTrue(replay.orElseThrow().replayed());
        assertAccepted(second.handleNext());
        assertAccepted(second.handleNext());
        assertAccepted(second.handleNext());

        EscrowSnapshot settled = stores.record(AuctionEscrowAuthority.aggregateId("auction-settle")).state().current().orElseThrow();
        EscrowSnapshot refunded = stores.record(AuctionEscrowAuthority.aggregateId("auction-refund")).state().current().orElseThrow();

        assertEquals("worker-incarnation-2", stores.decisions().getLast().workerIncarnation());
        assertEquals(8, stores.committedWatermark());
        assertEquals(EscrowStatus.SETTLED, settled.status());
        assertEquals(220, settled.releasePlan().orElseThrow().totalHeldMinor());
        assertEquals(220, settled.releasePlan().orElseThrow().totalPayoutMinor() + settled.releasePlan().orElseThrow().totalRefundedMinor());
        assertEquals(EscrowStatus.CANCELLED, refunded.status());
        assertEquals(60, refunded.releasePlan().orElseThrow().totalRefundedMinor());
        assertEquals(1, stores.emissionsFor("cmd-settle").size(), "settle response must be emitted once despite replay");
        assertFalse(stores.projections().isEmpty(), "projection cache must be rebuilt after restart");
    }

    private static AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker(
            String incarnation,
            DurableEscrowStores stores) {
        AuctionEscrowAuthority authority = new AuctionEscrowAuthority(stores.ledger);
        return new AuthorityRuntimeWorker<>(
                stores::poll,
                stores,
                authority::handle,
                (command, decision) -> stores.projections.put(
                        AuctionEscrowAuthority.projectionKey(command.envelope().payload().auctionId()),
                        decision.state().wireValue(decision.revision().value())),
                stores::publish,
                (delivery, decision) -> stores.decisions.add(new DecisionLog(
                        incarnation,
                        delivery.offset().position(),
                        decision.status(),
                        decision.revision(),
                        decision.replayed())),
                offset -> stores.committed = Math.max(stores.committed, offset.position() + 1));
    }

    private static void assertAccepted(Optional<AuthorityRuntimeReceipt> receipt) {
        assertTrue(receipt.isPresent());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, receipt.orElseThrow().status());
    }

    private static AuthorityBackendRegistrationReceipt admittedReceipt() {
        return new AuthorityBackendRegistrationReceipt(
                AuthorityBackendRegistrationStatus.ADMITTED,
                AuctionEscrowAuthority.descriptor().capabilityId(),
                AuthorityBackendDescriptorDigests.descriptorDigest(AuctionEscrowAuthority.descriptor()),
                "sha256:auction-escrow-backend",
                AuthorityBackendDescriptorDigests.sha256Hex("auction-escrow-plan"),
                Optional.of(PRINCIPAL),
                Optional.of(AuthorityBackendDescriptorDigests.sha256Hex("auction-escrow-grants")),
                FENCING_EPOCH,
                NOW,
                "receipt-auction-escrow",
                Optional.empty(),
                AuthorityBackendDescriptorDigests.sha256Hex("receipt-auction-escrow"));
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
                        AuctionEscrowAuthority.aggregateId(payload.auctionId()),
                        AuctionEscrowAuthority.CONTRACT,
                        commandName(payload),
                        trace(),
                        Optional.empty(),
                        payload),
                PRINCIPAL,
                FENCING_EPOCH,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + idempotencyKey,
                NOW);
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
                "trace-auction-escrow-tier2",
                "span-auction-escrow-tier2",
                Optional.empty(),
                NOW,
                "auction-escrow-tier2",
                new InstanceId("instance-auction-escrow-tier2"));
    }

    private static final class DurableEscrowStores
            implements sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore<AuctionEscrowState> {
        private final Map<AggregateId, AuthorityRecord<AuctionEscrowState>> records = new LinkedHashMap<>();
        private final List<AuthorityCommandDelivery<AuctionEscrowCommand>> log = new ArrayList<>();
        private final List<DecisionLog> decisions = new ArrayList<>();
        private final Map<String, String> projections = new HashMap<>();
        private final Map<String, List<AuthorityEmission>> emissionsByCommand = new HashMap<>();
        private final InMemoryIdempotencyLedger<AuctionEscrowState, AuctionEscrowReceipt> ledger = new InMemoryIdempotencyLedger<>();
        private long committed;

        void append(AuthorityCommand<AuctionEscrowCommand> command) {
            log.add(new AuthorityCommandDelivery<>(command, new AuthorityOffset("auction-escrow-log", 0, log.size())));
        }

        Optional<AuthorityCommandDelivery<AuctionEscrowCommand>> poll() {
            if (committed >= log.size()) {
                return Optional.empty();
            }
            return Optional.of(log.get((int) committed));
        }

        @Override
        public AuthorityRecord<AuctionEscrowState> load(AggregateId aggregateId) {
            return records.getOrDefault(aggregateId, AuctionEscrowAuthority.emptyRecord(FENCING_EPOCH));
        }

        @Override
        public void store(AggregateId aggregateId, AuthorityRecord<AuctionEscrowState> record) {
            records.put(aggregateId, record);
        }

        AuthorityRecord<AuctionEscrowState> record(AggregateId aggregateId) {
            return records.get(aggregateId);
        }

        void publish(AuthorityEmission emission) {
            if (emission.kind() == sh.harold.fulcrum.data.authority.AuthorityEmissionKind.RESPONSE) {
                emissionsByCommand.computeIfAbsent(emission.key(), ignored -> new ArrayList<>()).add(emission);
            }
        }

        List<AuthorityEmission> emissionsFor(String commandId) {
            return emissionsByCommand.getOrDefault(commandId, List.of());
        }

        List<DecisionLog> decisions() {
            return decisions;
        }

        Map<String, String> projections() {
            return projections;
        }

        void clearProjectionCache() {
            projections.clear();
        }

        long committedWatermark() {
            return committed;
        }
    }

    private record DecisionLog(
            String workerIncarnation,
            long offset,
            AuthorityDecisionStatus status,
            Revision revision,
            boolean replayed) {
    }
}
