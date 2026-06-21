package sh.harold.fulcrum.validation.escrowe2e;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
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
import sh.harold.fulcrum.validation.auctionescrow.EscrowHold;
import sh.harold.fulcrum.validation.auctionescrow.EscrowSnapshot;
import sh.harold.fulcrum.validation.auctionescrow.EscrowStatus;
import sh.harold.fulcrum.validation.auctionescrow.ReleaseLineKind;
import sh.harold.fulcrum.validation.auctionescrow.ReleasePlan;
import sh.harold.fulcrum.validation.auctionexperience.AhProxyCommand;
import sh.harold.fulcrum.validation.auctionexperience.AuctionCommandPort;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperience;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceReceipt;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceResult;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceSession;
import sh.harold.fulcrum.validation.auctionexperience.AuctionMenuClick;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public final class HeadlessAuctionBotWitness {
    public static final String SCHEMA = "fulcrum.validation.auction-escrow/headless-bot-restart-proof/v1";

    private static final Instant DEFAULT_NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final PrincipalId BACKEND_PRINCIPAL = new PrincipalId("auction-escrow-backend");
    private static final String AUCTION_ID = "auction-restart-proof";
    private static final String SESSION_ID = "headless-auction-restart-bot";

    public SettlementCertificate run() {
        return run(Options.semanticFixture(DEFAULT_NOW));
    }

    public SettlementCertificate run(Options options) {
        if (!"jvm-semantic-fixture".equals(options.executionMode())) {
            throw new IllegalStateException("HeadlessAuctionBotWitness only runs jvm-semantic-fixture mode");
        }
        if (options.deleteEscrowPod()) {
            throw new IllegalStateException("live pod deletion is reserved for the live-store witness, not the semantic fixture");
        }

        Instant now = options.now();
        String auctionId = options.auctionId();
        AuthorityBackendRegistrationReceipt registration = registerEscrowBackend(now);
        ScenarioRuntime runtime = new ScenarioRuntime(registration.fencingEpoch());
        GuardedAuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> firstWorker =
                AuthorityBackendRuntimeGuard.guard(registration, worker("escrow-worker-before-restart", runtime));
        AuctionExperience experience = new AuctionExperience(runtime, registration.fencingEpoch());
        AuctionExperienceSession session = new AuctionExperienceSession(SESSION_ID);
        List<TranscriptStep> transcript = new ArrayList<>();

        AuctionExperienceResult sellMenu = proxy(
                transcript,
                experience,
                session,
                new AhProxyCommand("seller", "/ah sell " + auctionId + " beacon COIN", "open-menu", now));
        require("Confirm Auction Listing".equals(sellMenu.menuView().title()), "sell command must render listing confirmation");
        require(sellMenu.receipts().isEmpty(), "sell menu render must not append an escrow command yet");

        submitAndApply(
                transcript,
                experience,
                session,
                firstWorker,
                AuctionMenuClick.confirmListing("seller", auctionId, "beacon", "COIN", "confirm-open", now.plusSeconds(1)));
        proxy(
                transcript,
                experience,
                session,
                new AhProxyCommand("bidder-low", "/ah browse " + auctionId, "browse-board", now.plusSeconds(2)));
        submitAndApply(
                transcript,
                experience,
                session,
                firstWorker,
                AuctionMenuClick.placeBid("bidder-low", auctionId, 100, "COIN", "bid-low", now.plusSeconds(3)));
        submitAndApply(
                transcript,
                experience,
                session,
                firstWorker,
                AuctionMenuClick.placeBid("bidder-high", auctionId, 175, "COIN", "bid-high", now.plusSeconds(4)));

        AuctionMenuClick closeClick = AuctionMenuClick.settle("seller", auctionId, "settle-close", now.plusSeconds(5));
        AuctionExperienceReceipt closeReceipt = submit(transcript, experience, session, closeClick);
        transcript.add(TranscriptStep.restart(
                "escrow-worker-before-restart",
                "escrow-worker-after-restart",
                runtime.committedWatermark(),
                runtime.pendingDeliveryCount()));

        GuardedAuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> replacementWorker =
                AuthorityBackendRuntimeGuard.guard(registration, worker("escrow-worker-after-restart", runtime));
        AuthorityRuntimeReceipt settledReceipt = requireAccepted(replacementWorker.handleNext());
        transcript.add(TranscriptStep.applied(
                "escrow-worker-after-restart",
                closeReceipt.commandId().value(),
                settledReceipt.revision().value(),
                settledReceipt.replayed()));

        AuctionExperienceReceipt duplicateCloseReceipt = submit(transcript, experience, session, closeClick);
        require(closeReceipt.commandId().equals(duplicateCloseReceipt.commandId()), "duplicate close must reuse command id");
        AuthorityRuntimeReceipt replayReceipt = requireAccepted(replacementWorker.handleNext());
        require(replayReceipt.replayed(), "duplicate close must replay the persisted settle decision");
        require(settledReceipt.revision().equals(replayReceipt.revision()), "duplicate close must keep the settled revision");
        transcript.add(TranscriptStep.applied(
                "escrow-worker-after-restart",
                duplicateCloseReceipt.commandId().value(),
                replayReceipt.revision().value(),
                replayReceipt.replayed()));

        EscrowSnapshot settled = runtime.record(AuctionEscrowContract.aggregateId(AUCTION_ID)).state().current().orElseThrow();
        SettlementCertificate certificate = SettlementCertificate.from(options, registration, runtime, settled, transcript, closeReceipt.commandId());
        certificate.assertConserved();
        return certificate;
    }

    private static AuctionExperienceResult proxy(
            List<TranscriptStep> transcript,
            AuctionExperience experience,
            AuctionExperienceSession session,
            AhProxyCommand command) {
        AuctionExperienceResult result = experience.handle(session, command);
        transcript.add(TranscriptStep.botCommand(
                command.playerId(),
                command.correlationId(),
                command.rawCommand(),
                result.menuView().title(),
                "rendered"));
        return result;
    }

    private static void submitAndApply(
            List<TranscriptStep> transcript,
            AuctionExperience experience,
            AuctionExperienceSession session,
            GuardedAuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker,
            AuctionMenuClick click) {
        AuctionExperienceReceipt receipt = submit(transcript, experience, session, click);
        AuthorityRuntimeReceipt runtimeReceipt = requireAccepted(worker.handleNext());
        transcript.add(TranscriptStep.applied(
                worker.registrationReceipt().principalId().orElseThrow().value(),
                receipt.commandId().value(),
                runtimeReceipt.revision().value(),
                runtimeReceipt.replayed()));
    }

    private static AuctionExperienceReceipt submit(
            List<TranscriptStep> transcript,
            AuctionExperience experience,
            AuctionExperienceSession session,
            AuctionMenuClick click) {
        AuctionExperienceResult result = experience.handle(session, click);
        require(result.receipts().size() == 1, "menu click must submit exactly one escrow command");
        AuctionExperienceReceipt receipt = result.receipts().getFirst();
        require(AuctionEscrowContract.CONTRACT.equals(receipt.contractName()), "receipt must target auction.escrow.v1");
        transcript.add(TranscriptStep.botCommand(
                click.playerId(),
                click.correlationId(),
                click.action().name(),
                receipt.commandName().value(),
                "submitted"));
        return receipt;
    }

    private static AuthorityRuntimeReceipt requireAccepted(Optional<AuthorityRuntimeReceipt> receipt) {
        require(receipt.isPresent(), "expected authority runtime receipt");
        AuthorityRuntimeReceipt runtimeReceipt = receipt.orElseThrow();
        require(runtimeReceipt.status() == AuthorityDecisionStatus.ACCEPTED, "authority command must be accepted");
        return runtimeReceipt;
    }

    private static AuthorityBackendRegistrationReceipt registerEscrowBackend(Instant now) {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        return controller.register(AuthorityBackendRegistrationRequest.credentialed(
                AuctionEscrowContract.descriptor(),
                new HostSecurityContext(
                        new HostInstanceIdentity(
                                new InstanceId("instance-headless-auction-restart"),
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
                now));
    }

    private static AuthorityArtifactVerificationEvidence verification(String digest) {
        return AuthorityArtifactVerificationEvidence.verified(
                "OCI",
                "oci://ghcr.io/harolddotsh/auction-escrow-backend@" + digest,
                digest,
                "cosign:test");
    }

    private static AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker(
            String incarnation,
            ScenarioRuntime runtime) {
        AuctionEscrowAuthority authority = new AuctionEscrowAuthority(runtime.ledger);
        return new AuthorityRuntimeWorker<>(
                runtime::poll,
                runtime,
                authority::handle,
                (command, decision) -> runtime.projections.put(
                        AuctionEscrowContract.projectionKey(command.envelope().payload().auctionId()),
                        decision.state().current().map(snapshot -> snapshot.status().name()).orElse("empty")),
                runtime::publish,
                (delivery, decision) -> runtime.decisions.add(new DecisionTrace(
                        incarnation,
                        delivery.command().envelope().commandId().value(),
                        delivery.offset().position(),
                        decision.status(),
                        decision.revision(),
                        decision.replayed())),
                offset -> runtime.committed = Math.max(runtime.committed, offset.position() + 1));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public record Options(
            String executionMode,
            boolean deleteEscrowPod,
            Instant now,
            String auctionId,
            String witnessAttemptId,
            Duration operationTimeout) {
        public Options {
            executionMode = requireNonBlank(executionMode, "executionMode");
            now = now == null ? DEFAULT_NOW : now;
            auctionId = requireNonBlank(auctionId, "auctionId");
            witnessAttemptId = requireNonBlank(witnessAttemptId, "witnessAttemptId");
            operationTimeout = operationTimeout == null ? Duration.ofSeconds(30) : operationTimeout;
            if (operationTimeout.isZero() || operationTimeout.isNegative()) {
                throw new IllegalArgumentException("operationTimeout must be positive");
            }
        }

        static Options semanticFixture(Instant now) {
            return new Options("jvm-semantic-fixture", false, now, AUCTION_ID, "semantic-fixture", Duration.ofSeconds(30));
        }

        static Options from(Map<String, String> environment) {
            String mode = environment.getOrDefault("FULCRUM_WITNESS_MODE", "jvm-semantic-fixture");
            boolean deletePod = Boolean.parseBoolean(environment.getOrDefault("FULCRUM_WITNESS_DELETE_ESCROW_POD", "false"));
            Instant now = Optional.ofNullable(environment.get("FULCRUM_WITNESS_NOW"))
                    .filter(value -> !value.isBlank())
                    .map(Instant::parse)
                    .orElse(DEFAULT_NOW);
            String attemptId = environment.getOrDefault("FULCRUM_WITNESS_ATTEMPT_ID", defaultAttemptId(mode));
            String auctionId = environment.getOrDefault("FULCRUM_WITNESS_AUCTION_ID", defaultAuctionId(mode, attemptId));
            Duration operationTimeout = Duration.ofSeconds(Long.parseLong(
                    environment.getOrDefault("FULCRUM_WITNESS_LIVE_TIMEOUT_SECONDS", "30")));
            return new Options(mode, deletePod, now, auctionId, attemptId, operationTimeout);
        }

        private static String defaultAttemptId(String mode) {
            if ("live-store".equals(mode)) {
                return "live-store-" + UUID.randomUUID();
            }
            return "semantic-fixture";
        }

        private static String defaultAuctionId(String mode, String attemptId) {
            if ("live-store".equals(mode)) {
                return "auction-restart-proof-" + attemptId.replaceAll("[^A-Za-z0-9-]", "-");
            }
            return AUCTION_ID;
        }
    }

    public record TranscriptStep(
            String kind,
            String actor,
            String correlationId,
            String action,
            String result,
            long revision,
            boolean replayed) {
        static TranscriptStep botCommand(
                String actor,
                String correlationId,
                String action,
                String result,
                String outcome) {
            return new TranscriptStep("bot-command", actor, correlationId, action, result + ":" + outcome, -1, false);
        }

        static TranscriptStep restart(
                String previousWorker,
                String nextWorker,
                long committedWatermark,
                long pendingDeliveries) {
            return new TranscriptStep(
                    "restart",
                    previousWorker + "->" + nextWorker,
                    "worker-replacement",
                    "replace-worker",
                    "committed=" + committedWatermark + "|pending=" + pendingDeliveries,
                    -1,
                    false);
        }

        static TranscriptStep applied(
                String worker,
                String commandId,
                long revision,
                boolean replayed) {
            return new TranscriptStep("worker-apply", worker, commandId, "apply", "accepted", revision, replayed);
        }

        static TranscriptStep checkpoint(String name, String result, long revision) {
            return new TranscriptStep("checkpoint", "escrow-e2e-witness", name, "checkpoint", result, revision, false);
        }

        String toJson() {
            return "{"
                    + "\"kind\":" + quote(kind)
                    + ",\"actor\":" + quote(actor)
                    + ",\"correlationId\":" + quote(correlationId)
                    + ",\"action\":" + quote(action)
                    + ",\"result\":" + quote(result)
                    + ",\"revision\":" + revision
                    + ",\"replayed\":" + replayed
                    + "}";
        }
    }

    private record DecisionTrace(
            String workerIncarnation,
            String commandId,
            long offset,
            AuthorityDecisionStatus status,
            Revision revision,
            boolean replayed) {
    }

    public record SettlementCertificate(
            String schema,
            String executionMode,
            boolean podDeletionRequested,
            String auctionId,
            String winningBidder,
            long totalHeldMinor,
            long totalPayoutMinor,
            long totalRefundedMinor,
            String releasePlanFingerprint,
            int settleResponseEmissions,
            long replayedCloseDecisions,
            List<String> workerIncarnations,
            List<TranscriptStep> transcript) {
        public SettlementCertificate {
            schema = requireNonBlank(schema, "schema");
            executionMode = requireNonBlank(executionMode, "executionMode");
            auctionId = requireNonBlank(auctionId, "auctionId");
            winningBidder = requireNonBlank(winningBidder, "winningBidder");
            releasePlanFingerprint = requireNonBlank(releasePlanFingerprint, "releasePlanFingerprint");
            workerIncarnations = List.copyOf(workerIncarnations);
            transcript = List.copyOf(transcript);
        }

        static SettlementCertificate from(
                Options options,
                AuthorityBackendRegistrationReceipt registration,
                ScenarioRuntime runtime,
                EscrowSnapshot snapshot,
                List<TranscriptStep> transcript,
                CommandId closeCommandId) {
            require(registration.status() == AuthorityBackendRegistrationStatus.ADMITTED, "registration must be admitted");
            require(AuthorityBackendDescriptorDigests.descriptorDigest(AuctionEscrowContract.descriptor())
                    .equals(registration.descriptorDigest()), "registration descriptor digest must match escrow contract");
            require(snapshot.status() == EscrowStatus.SETTLED, "auction must settle");
            ReleasePlan releasePlan = snapshot.releasePlan().orElseThrow();
            EscrowHold winner = snapshot.holds().stream()
                    .max(Comparator.comparingLong(EscrowHold::amountMinor)
                            .thenComparing(Comparator.comparingLong(EscrowHold::sequence).reversed()))
                    .orElseThrow();
            List<String> workers = runtime.decisions.stream()
                    .map(DecisionTrace::workerIncarnation)
                    .distinct()
                    .toList();
            long replayedClose = runtime.decisions.stream()
                    .filter(decision -> decision.commandId().equals(closeCommandId.value()))
                    .filter(DecisionTrace::replayed)
                    .count();
            long payoutLines = releasePlan.lines().stream()
                    .filter(line -> line.kind() == ReleaseLineKind.WINNER_PAYOUT)
                    .count();
            require(payoutLines == 1, "settlement must have exactly one winner payout line");
            return new SettlementCertificate(
                    SCHEMA,
                    options.executionMode(),
                    options.deleteEscrowPod(),
                    snapshot.auctionId(),
                    winner.bidderId(),
                    releasePlan.totalHeldMinor(),
                    releasePlan.totalPayoutMinor(),
                    releasePlan.totalRefundedMinor(),
                    releasePlan.fingerprint(),
                    runtime.responseEmissionsFor(closeCommandId).size(),
                    replayedClose,
                    workers,
                    List.copyOf(transcript));
        }

        public void assertConserved() {
            require(SCHEMA.equals(schema), "certificate schema mismatch");
            require(totalHeldMinor == totalPayoutMinor + totalRefundedMinor, "settlement must conserve held value");
            require(settleResponseEmissions == 1, "settle response must be emitted exactly once");
            require(replayedCloseDecisions == 1, "duplicate close must replay exactly once");
            if ("jvm-semantic-fixture".equals(executionMode) || podDeletionRequested) {
                require(transcript.stream().anyMatch(step -> step.kind().equals("restart")), "transcript must record worker replacement");
            } else {
                require(transcript.stream().anyMatch(step -> step.kind().equals("checkpoint")
                                && step.correlationId().equals("durable-close")),
                        "live-store transcript must record durable-close checkpoint");
            }
            require(transcript.stream().anyMatch(step -> step.kind().equals("worker-apply") && step.replayed()),
                    "transcript must record replayed close apply");
            require(transcript.stream()
                    .filter(step -> step.kind().equals("bot-command"))
                    .anyMatch(step -> step.action().equals("SETTLE")), "transcript must include bot close action");
        }

        public String toJson() {
            return "{"
                    + "\"schema\":" + quote(schema)
                    + ",\"executionMode\":" + quote(executionMode)
                    + ",\"podDeletionRequested\":" + podDeletionRequested
                    + ",\"auctionId\":" + quote(auctionId)
                    + ",\"winningBidder\":" + quote(winningBidder)
                    + ",\"totalHeldMinor\":" + totalHeldMinor
                    + ",\"totalPayoutMinor\":" + totalPayoutMinor
                    + ",\"totalRefundedMinor\":" + totalRefundedMinor
                    + ",\"releasePlanFingerprint\":" + quote(releasePlanFingerprint)
                    + ",\"settleResponseEmissions\":" + settleResponseEmissions
                    + ",\"replayedCloseDecisions\":" + replayedCloseDecisions
                    + ",\"workerIncarnations\":" + jsonStringArray(workerIncarnations)
                    + ",\"transcript\":" + transcript.stream()
                            .map(TranscriptStep::toJson)
                            .collect(Collectors.joining(",", "[", "]"))
                    + "}";
        }
    }

    private static final class ScenarioRuntime
            implements AuctionCommandPort, sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore<AuctionEscrowState> {
        private final long fencingEpoch;
        private final Map<AggregateId, AuthorityRecord<AuctionEscrowState>> records = new LinkedHashMap<>();
        private final List<AuthorityCommandDelivery<AuctionEscrowCommand>> deliveries = new ArrayList<>();
        private final List<AuthorityCommand<AuctionEscrowCommand>> commandLog = new ArrayList<>();
        private final List<DecisionTrace> decisions = new ArrayList<>();
        private final Map<String, String> projections = new HashMap<>();
        private final Map<String, List<AuthorityEmission>> responseEmissionsByCommand = new HashMap<>();
        private final InMemoryIdempotencyLedger<AuctionEscrowState, AuctionEscrowReceipt> ledger = new InMemoryIdempotencyLedger<>();
        private long committed;

        private ScenarioRuntime(long fencingEpoch) {
            this.fencingEpoch = fencingEpoch;
        }

        @Override
        public AuctionExperienceReceipt append(AuthorityCommand<AuctionEscrowCommand> command) {
            commandLog.add(command);
            deliveries.add(new AuthorityCommandDelivery<>(command, new AuthorityOffset("headless-auction-bot-log", 0, deliveries.size())));
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
            if (emission.kind() == AuthorityEmissionKind.RESPONSE) {
                responseEmissionsByCommand
                        .computeIfAbsent(emission.key(), ignored -> new ArrayList<>())
                        .add(emission);
            }
        }

        long committedWatermark() {
            return committed;
        }

        long pendingDeliveryCount() {
            return deliveries.size() - committed;
        }

        List<AuthorityEmission> responseEmissionsFor(CommandId commandId) {
            return responseEmissionsByCommand.getOrDefault(commandId.value(), List.of());
        }
    }

    private static String jsonStringArray(List<String> values) {
        return values.stream().map(HeadlessAuctionBotWitness::quote).collect(Collectors.joining(",", "[", "]"));
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(current);
            }
        }
        return escaped.append('"').toString();
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
