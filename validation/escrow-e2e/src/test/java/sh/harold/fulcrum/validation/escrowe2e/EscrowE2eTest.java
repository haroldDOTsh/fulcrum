package sh.harold.fulcrum.validation.escrowe2e;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRuntimeGuard;
import sh.harold.fulcrum.sdk.authority.GuardedAuthorityRuntimeWorker;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowAuthority;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowContract;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowReceipt;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowState;
import sh.harold.fulcrum.validation.auctionescrow.EscrowSnapshot;
import sh.harold.fulcrum.validation.auctionescrow.EscrowStatus;
import sh.harold.fulcrum.validation.auctionescrow.ReleasePlan;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EscrowE2eTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final PrincipalId BACKEND_PRINCIPAL = new PrincipalId("auction-escrow-backend");

    @Test
    void registeredEscrowBackendSettlesAuctionDrivenThroughExperienceSurface() {
        AuthorityBackendRegistrationReceipt registration = registerEscrowBackend();
        EscrowRuntime runtime = new EscrowRuntime(registration.fencingEpoch());
        GuardedAuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker =
                AuthorityBackendRuntimeGuard.guard(registration, worker(runtime));
        AuctionExperience experience = new AuctionExperience(runtime, registration.fencingEpoch());
        AuctionExperienceSession session = new AuctionExperienceSession("escrow-e2e-bot-session");

        AuctionExperienceResult sell = experience.handle(
                session,
                new AhProxyCommand("seller", "/ah sell auction-e2e beacon COIN", "sell-menu", NOW));
        assertEquals("Confirm Auction Listing", sell.menuView().title());

        assertSubmitted(
                experience.handle(
                        session,
                        AuctionMenuClick.confirmListing("seller", "auction-e2e", "beacon", "COIN", "open", NOW.plusSeconds(1))),
                AuctionEscrowContract.OPEN.value());
        assertAccepted(worker.handleNext());
        experience.handle(session, new AhProxyCommand("bidder-high", "/ah browse auction-e2e", "browse", NOW.plusSeconds(2)));

        assertSubmitted(
                experience.handle(
                        session,
                        AuctionMenuClick.placeBid("bidder-low", "auction-e2e", 100, "COIN", "bid-low", NOW.plusSeconds(3))),
                AuctionEscrowContract.HOLD.value());
        assertAccepted(worker.handleNext());
        assertSubmitted(
                experience.handle(
                        session,
                        AuctionMenuClick.placeBid("bidder-high", "auction-e2e", 175, "COIN", "bid-high", NOW.plusSeconds(4))),
                AuctionEscrowContract.HOLD.value());
        assertAccepted(worker.handleNext());
        assertSubmitted(
                experience.handle(
                        session,
                        AuctionMenuClick.settle("seller", "auction-e2e", "settle", NOW.plusSeconds(5))),
                AuctionEscrowContract.SETTLE.value());
        assertAccepted(worker.handleNext());

        EscrowSnapshot settled = runtime.record(AuctionEscrowContract.aggregateId("auction-e2e")).state().current().orElseThrow();
        ReleasePlan plan = settled.releasePlan().orElseThrow();
        assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, registration.status());
        assertEquals(AuctionEscrowContract.descriptor().capabilityId(), registration.capabilityId());
        assertEquals(AuthorityBackendDescriptorDigests.descriptorDigest(AuctionEscrowContract.descriptor()), registration.descriptorDigest());
        assertEquals(EscrowStatus.SETTLED, settled.status());
        assertEquals(275, plan.totalHeldMinor());
        assertEquals(175, plan.totalPayoutMinor());
        assertEquals(100, plan.totalRefundedMinor());
        assertEquals("seller", plan.lines().get(1).recipientId());
        assertEquals(4, runtime.commandLog().size());
        assertTrue(runtime.commandLog().stream()
                .allMatch(command -> command.envelope().contractName().equals(AuctionEscrowContract.CONTRACT)));
        assertTrue(runtime.projections().containsKey(AuctionEscrowContract.projectionKey("auction-e2e")));
    }

    private static AuthorityBackendRegistrationReceipt registerEscrowBackend() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        return controller.register(AuthorityBackendRegistrationRequest.credentialed(
                AuctionEscrowContract.descriptor(),
                new HostSecurityContext(
                        new HostInstanceIdentity(
                                new InstanceId("instance-auction-escrow-e2e"),
                                "authority-backend",
                                new PoolId("pool-authority"),
                                new MachineRef("machine-authority"),
                                BACKEND_PRINCIPAL),
                        "service-account:auction-escrow-e2e",
                        HostCredentialScope.of(
                                AuthorityBackendGrants.authorityDomain(AuctionEscrowContract.AUTHORITY_DOMAIN),
                                AuthorityBackendGrants.resourceClass(AuctionEscrowContract.RESOURCE_CLASS))),
                "sha256:auction-escrow-e2e",
                verification("sha256:auction-escrow-e2e"),
                NOW));
    }

    private static AuthorityArtifactVerificationEvidence verification(String digest) {
        return AuthorityArtifactVerificationEvidence.verified(
                "OCI",
                "oci://ghcr.io/sh-harold/auction-escrow-backend@" + digest,
                digest,
                "cosign:test");
    }

    private static AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker(EscrowRuntime runtime) {
        AuctionEscrowAuthority authority = new AuctionEscrowAuthority(runtime.ledger);
        return new AuthorityRuntimeWorker<>(
                runtime::poll,
                runtime,
                authority::handle,
                (command, decision) -> runtime.projections.put(
                        AuctionEscrowContract.projectionKey(command.envelope().payload().auctionId()),
                        decision.state().current().map(snapshot -> snapshot.status().name()).orElse("empty")),
                runtime::publish,
                (delivery, decision) -> runtime.decisions.add(decision.status()),
                offset -> runtime.committed = Math.max(runtime.committed, offset.position() + 1));
    }

    private static void assertSubmitted(AuctionExperienceResult result, String commandName) {
        assertEquals(1, result.receipts().size());
        AuctionExperienceReceipt receipt = result.receipts().getFirst();
        assertEquals(AuctionEscrowContract.CONTRACT, receipt.contractName());
        assertEquals(commandName, receipt.commandName().value());
    }

    private static void assertAccepted(Optional<AuthorityRuntimeReceipt> receipt) {
        assertTrue(receipt.isPresent());
        assertEquals(AuthorityDecisionStatus.ACCEPTED, receipt.orElseThrow().status());
    }

    private static final class EscrowRuntime
            implements AuctionCommandPort, sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore<AuctionEscrowState> {
        private final long fencingEpoch;
        private final Map<AggregateId, AuthorityRecord<AuctionEscrowState>> records = new LinkedHashMap<>();
        private final List<AuthorityCommandDelivery<AuctionEscrowCommand>> deliveries = new ArrayList<>();
        private final List<AuthorityCommand<AuctionEscrowCommand>> commandLog = new ArrayList<>();
        private final List<AuthorityDecisionStatus> decisions = new ArrayList<>();
        private final Map<String, String> projections = new HashMap<>();
        private final List<AuthorityEmission> emissions = new ArrayList<>();
        private final InMemoryIdempotencyLedger<AuctionEscrowState, AuctionEscrowReceipt> ledger = new InMemoryIdempotencyLedger<>();
        private long committed;

        private EscrowRuntime(long fencingEpoch) {
            this.fencingEpoch = fencingEpoch;
        }

        @Override
        public AuctionExperienceReceipt append(AuthorityCommand<AuctionEscrowCommand> command) {
            commandLog.add(command);
            deliveries.add(new AuthorityCommandDelivery<>(command, new AuthorityOffset("escrow-e2e-log", 0, deliveries.size())));
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
            return records.getOrDefault(aggregateId, AuctionEscrowAuthority.emptyRecord(fencingEpoch));
        }

        @Override
        public void store(AggregateId aggregateId, AuthorityRecord<AuctionEscrowState> record) {
            records.put(aggregateId, record);
        }

        AuthorityRecord<AuctionEscrowState> record(AggregateId aggregateId) {
            return records.get(aggregateId);
        }

        void publish(AuthorityEmission emission) {
            emissions.add(emission);
        }

        List<AuthorityCommand<AuctionEscrowCommand>> commandLog() {
            return commandLog;
        }

        Map<String, String> projections() {
            return projections;
        }
    }
}
