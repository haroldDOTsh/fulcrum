package sh.harold.fulcrum.validation.auctionescrow;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.validation.auctionexperience.AhProxyCommand;
import sh.harold.fulcrum.validation.auctionexperience.AuctionCommandPort;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperience;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceReceipt;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceResult;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceSession;
import sh.harold.fulcrum.validation.auctionexperience.AuctionMenuClick;

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

final class AuctionExperienceBotEscrowIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final long FENCING_EPOCH = 23;

    @Test
    void botDrivesFullAuctionThroughExperienceToEscrowAuthority() {
        EscrowRuntime runtime = new EscrowRuntime();
        AuctionExperience experience = new AuctionExperience(runtime, FENCING_EPOCH);
        AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker = worker(runtime);
        AuctionExperienceSession session = new AuctionExperienceSession("bot-auction-session");

        AuctionExperienceResult sellMenu = experience.handle(
                session,
                new AhProxyCommand("seller", "/ah sell auction-alpha beacon COIN", "proxy-sell", NOW));
        assertEquals("Confirm Auction Listing", sellMenu.menuView().title());
        assertTrue(sellMenu.receipts().isEmpty(), "/ah sell should route to the confirmation menu before escrow append");

        AuctionExperienceResult open = experience.handle(
                session,
                AuctionMenuClick.confirmListing("seller", "auction-alpha", "beacon", "COIN", "confirm-open", NOW.plusSeconds(1)));
        assertReceipt(open, AuctionEscrowContract.OPEN);
        assertAccepted(worker.handleNext());

        AuctionExperienceResult browse = experience.handle(
                session,
                new AhProxyCommand("bidder-low", "/ah browse auction-alpha", "proxy-browse", NOW.plusSeconds(2)));
        assertEquals("Auction Board", browse.menuView().title());

        assertReceipt(
                experience.handle(
                        session,
                        AuctionMenuClick.placeBid("bidder-low", "auction-alpha", 100, "COIN", "bid-low", NOW.plusSeconds(3))),
                AuctionEscrowContract.HOLD);
        assertAccepted(worker.handleNext());
        assertReceipt(
                experience.handle(
                        session,
                        AuctionMenuClick.placeBid("bidder-high", "auction-alpha", 150, "COIN", "bid-high", NOW.plusSeconds(4))),
                AuctionEscrowContract.HOLD);
        assertAccepted(worker.handleNext());
        assertReceipt(
                experience.handle(
                        session,
                        AuctionMenuClick.settle("seller", "auction-alpha", "settle", NOW.plusSeconds(5))),
                AuctionEscrowContract.SETTLE);
        assertAccepted(worker.handleNext());

        EscrowSnapshot settled = runtime.record(AuctionEscrowContract.aggregateId("auction-alpha")).state().current().orElseThrow();
        ReleasePlan plan = settled.releasePlan().orElseThrow();
        assertEquals(EscrowStatus.SETTLED, settled.status());
        assertEquals(250, plan.totalHeldMinor());
        assertEquals(150, plan.totalPayoutMinor());
        assertEquals(100, plan.totalRefundedMinor());
        assertEquals("seller", plan.lines().get(1).recipientId());
        assertEquals(4, runtime.commandLog().size());
        assertTrue(runtime.commandLog().stream()
                .allMatch(command -> command.envelope().contractName().equals(AuctionEscrowContract.CONTRACT)));
        assertFalse(runtime.projections().isEmpty(), "experience-driven flow must materialize escrow projections");
    }

    private static AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker(EscrowRuntime runtime) {
        AuctionEscrowAuthority authority = new AuctionEscrowAuthority(runtime.ledger);
        return new AuthorityRuntimeWorker<>(
                runtime::poll,
                runtime,
                authority::handle,
                (command, decision) -> runtime.projections.put(
                        AuctionEscrowContract.projectionKey(command.envelope().payload().auctionId()),
                        decision.state().wireValue(decision.revision().value())),
                runtime::publish,
                (delivery, decision) -> runtime.decisions.add(decision.status()),
                offset -> runtime.committed = Math.max(runtime.committed, offset.position() + 1));
    }

    private static void assertReceipt(AuctionExperienceResult result, sh.harold.fulcrum.api.contract.CommandName commandName) {
        assertEquals(1, result.receipts().size());
        AuctionExperienceReceipt receipt = result.receipts().getFirst();
        assertEquals(AuctionEscrowContract.CONTRACT, receipt.contractName());
        assertEquals(commandName, receipt.commandName());
    }

    private static void assertAccepted(Optional<AuthorityRuntimeReceipt> receipt) {
        assertTrue(receipt.isPresent());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, receipt.orElseThrow().status());
    }

    private static final class EscrowRuntime
            implements AuctionCommandPort, sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore<AuctionEscrowState> {
        private final Map<AggregateId, AuthorityRecord<AuctionEscrowState>> records = new LinkedHashMap<>();
        private final List<AuthorityCommandDelivery<AuctionEscrowCommand>> deliveries = new ArrayList<>();
        private final List<AuthorityCommand<AuctionEscrowCommand>> commandLog = new ArrayList<>();
        private final List<AuthorityDecisionStatus> decisions = new ArrayList<>();
        private final Map<String, String> projections = new HashMap<>();
        private final InMemoryIdempotencyLedger<AuctionEscrowState, AuctionEscrowReceipt> ledger = new InMemoryIdempotencyLedger<>();
        private long committed;

        @Override
        public AuctionExperienceReceipt append(AuthorityCommand<AuctionEscrowCommand> command) {
            commandLog.add(command);
            deliveries.add(new AuthorityCommandDelivery<>(command, new AuthorityOffset("auction-experience-log", 0, deliveries.size())));
            return new AuctionExperienceReceipt(
                    command.envelope().commandId(),
                    command.envelope().contractName(),
                    command.envelope().commandName(),
                    command.envelope().aggregateId(),
                    command.envelope().idempotencyKey().value());
        }

        Optional<AuthorityCommandDelivery<AuctionEscrowCommand>> poll() {
            if (committed >= deliveries.size()) {
                return Optional.empty();
            }
            return Optional.of(deliveries.get((int) committed));
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

        void publish(AuthorityEmission ignored) {
        }

        List<AuthorityCommand<AuctionEscrowCommand>> commandLog() {
            return commandLog;
        }

        Map<String, String> projections() {
            return projections;
        }
    }
}
