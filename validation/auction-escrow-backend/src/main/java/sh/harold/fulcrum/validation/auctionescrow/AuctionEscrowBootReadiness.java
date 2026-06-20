package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityOffset;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeReceipt;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRuntimeGuard;
import sh.harold.fulcrum.sdk.authority.GuardedAuthorityRuntimeWorker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class AuctionEscrowBootReadiness {
    private static final String OFFSET_SOURCE = "auction-escrow-boot-probe";

    private AuctionEscrowBootReadiness() {
    }

    static AuctionEscrowReadinessEvidence prove(
            AuctionEscrowBackendConfig config,
            AuthorityBackendRegistrationReceipt registrationReceipt,
            Instant generatedAt,
            String bootNonce) {
        Objects.requireNonNull(config, "config");
        AuthorityBackendRegistrationReceipt receipt = Objects.requireNonNull(registrationReceipt, "registrationReceipt");
        Instant now = Objects.requireNonNull(generatedAt, "generatedAt");
        String checkedBootNonce = requireNonBlank(bootNonce, "bootNonce");
        long fencingEpoch = receipt.fencingEpoch();
        BootProbeRuntime runtime = new BootProbeRuntime(fencingEpoch);
        runtime.append(bootCommand(config, fencingEpoch, now, checkedBootNonce));
        GuardedAuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker =
                AuthorityBackendRuntimeGuard.guard(receipt, worker(runtime));
        AuthorityRuntimeReceipt runtimeReceipt = worker.handleNext()
                .orElseThrow(() -> new IllegalStateException("boot probe did not produce runtime receipt"));
        return AuctionEscrowReadinessEvidence.from(
                config,
                worker.registrationReceipt(),
                fencingEpoch,
                runtimeReceipt,
                checkedBootNonce,
                runtime.applyCount(),
                now);
    }

    private static AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker(
            BootProbeRuntime runtime) {
        AuctionEscrowAuthority authority = new AuctionEscrowAuthority(runtime.ledger);
        return new AuthorityRuntimeWorker<>(
                runtime::poll,
                runtime,
                authority::handle,
                (command, decision) -> runtime.recordProjection(command, decision),
                runtime::publish,
                runtime::recordDecision,
                offset -> runtime.committed = Math.max(runtime.committed, offset.position() + 1));
    }

    static AuthorityCommand<AuctionEscrowCommand> bootCommand(
            AuctionEscrowBackendConfig config,
            AuthorityBackendRegistrationReceipt receipt,
            Instant now,
            String bootNonce) {
        return bootCommand(config, receipt.fencingEpoch(), now, bootNonce);
    }

    static AuthorityCommand<AuctionEscrowCommand> bootCommand(
            AuctionEscrowBackendConfig config,
            long fencingEpoch,
            Instant now,
            String bootNonce) {
        if (fencingEpoch <= 0) {
            throw new IllegalArgumentException("fencingEpoch must be positive");
        }
        String nonceDigest = AuthorityBackendDescriptorDigests.sha256Hex(bootNonce);
        String auctionId = "boot-" + nonceDigest.substring(0, 16);
        OpenEscrow payload = new OpenEscrow(
                auctionId,
                config.securityContext().identity().principalId().value(),
                "boot-readiness-sentinel",
                "READY",
                now);
        String idempotencyKey = "boot-readiness:" + nonceDigest;
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("boot-readiness-" + nonceDigest.substring(0, 16)),
                        new IdempotencyKey(idempotencyKey),
                        config.securityContext().identity().principalId(),
                        AuctionEscrowAuthority.aggregateId(payload.auctionId()),
                        AuctionEscrowAuthority.CONTRACT,
                        AuctionEscrowContract.commandName(payload),
                        trace(config, now, nonceDigest),
                        Optional.empty(),
                        payload),
                config.securityContext().identity().principalId(),
                fencingEpoch,
                Optional.of(new Revision(0)),
                AuctionEscrowContract.payloadFingerprint(payload, idempotencyKey),
                now);
    }

    private static TraceEnvelope trace(AuctionEscrowBackendConfig config, Instant now, String nonceDigest) {
        return new TraceEnvelope(
                "trace-auction-escrow-readiness-" + nonceDigest.substring(0, 16),
                "span-auction-escrow-readiness",
                Optional.empty(),
                now,
                "auction-escrow-backend",
                new InstanceId(config.securityContext().identity().instanceId().value()));
    }

    private static String requireNonBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    private static final class BootProbeRuntime implements AuthorityRecordStore<AuctionEscrowState> {
        private final long fencingEpoch;
        private final Map<AggregateId, AuthorityRecord<AuctionEscrowState>> records = new LinkedHashMap<>();
        private final List<AuthorityCommandDelivery<AuctionEscrowCommand>> deliveries = new ArrayList<>();
        private final List<AuthorityEmission> emissions = new ArrayList<>();
        private final List<AuthorityRuntimeReceipt> receipts = new ArrayList<>();
        private final InMemoryIdempotencyLedger<AuctionEscrowState, AuctionEscrowReceipt> ledger =
                new InMemoryIdempotencyLedger<>();
        private long committed;
        private long applyCount;

        private BootProbeRuntime(long fencingEpoch) {
            this.fencingEpoch = fencingEpoch;
        }

        void append(AuthorityCommand<AuctionEscrowCommand> command) {
            deliveries.add(new AuthorityCommandDelivery<>(
                    command,
                    new AuthorityOffset(OFFSET_SOURCE, 0, deliveries.size())));
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

        void recordProjection(
                AuthorityCommand<AuctionEscrowCommand> ignoredCommand,
                AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> ignoredDecision) {
            applyCount++;
        }

        void publish(AuthorityEmission emission) {
            emissions.add(emission);
        }

        void recordDecision(
                AuthorityCommandDelivery<AuctionEscrowCommand> delivery,
                AuthorityDecision<AuctionEscrowState, AuctionEscrowReceipt> decision) {
            receipts.add(new AuthorityRuntimeReceipt(
                    delivery.offset(),
                    delivery.command().envelope().aggregateId(),
                    decision.status(),
                    decision.revision(),
                    decision.replayed()));
        }

        long applyCount() {
            return applyCount;
        }
    }
}
