package sh.harold.fulcrum.validation.escrowe2e;

import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowCommand;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowContract;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowLiveStoreClient;
import sh.harold.fulcrum.validation.auctionescrow.EscrowStatus;
import sh.harold.fulcrum.validation.auctionexperience.AhProxyCommand;
import sh.harold.fulcrum.validation.auctionexperience.AuctionCommandPort;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperience;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceReceipt;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceResult;
import sh.harold.fulcrum.validation.auctionexperience.AuctionExperienceSession;
import sh.harold.fulcrum.validation.auctionexperience.AuctionMenuClick;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class LiveStoreHeadlessAuctionBotWitness {
    private static final String SESSION_ID = "headless-auction-restart-bot";
    private static final String WORKER = "auction-escrow-live-store";

    HeadlessAuctionBotWitness.SettlementCertificate run(
            HeadlessAuctionBotWitness.Options options,
            Map<String, String> environment) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(environment, "environment");
        if (!"live-store".equals(options.executionMode())) {
            throw new IllegalStateException("LiveStoreHeadlessAuctionBotWitness only runs live-store mode");
        }

        AuctionEscrowLiveStoreClient.Config config = AuctionEscrowLiveStoreClient.Config.from(
                environment,
                "escrow-e2e-witness-" + options.witnessAttemptId(),
                "escrow-e2e-witness-" + options.witnessAttemptId());
        Optional<KubernetesEscrowPodDisruptor> disruptor = options.deleteEscrowPod()
                ? Optional.of(disrupt(environment))
                : Optional.empty();
        try (AuctionEscrowLiveStoreClient client = AuctionEscrowLiveStoreClient.open(config)) {
            return run(options, client, disruptor);
        }
    }

    HeadlessAuctionBotWitness.SettlementCertificate run(
            HeadlessAuctionBotWitness.Options options,
            AuctionEscrowLiveStoreClient client) {
        return run(options, client, Optional.empty());
    }

    private HeadlessAuctionBotWitness.SettlementCertificate run(
            HeadlessAuctionBotWitness.Options options,
            AuctionEscrowLiveStoreClient client,
            Optional<KubernetesEscrowPodDisruptor> disruptor) {
        Duration timeout = options.operationTimeout();
        Instant now = options.now();
        String auctionId = options.auctionId();
        String aggregateId = AuctionEscrowContract.aggregateId(auctionId).value();
        long fencingEpoch = client.awaitFencingEpoch(timeout);

        LiveAuctionCommandPort commandPort = new LiveAuctionCommandPort(client);
        AuctionExperience experience = new AuctionExperience(commandPort, fencingEpoch);
        AuctionExperienceSession session = new AuctionExperienceSession(SESSION_ID + "-" + options.witnessAttemptId());
        List<HeadlessAuctionBotWitness.TranscriptStep> transcript = new ArrayList<>();

        AuctionExperienceResult sellMenu = proxy(
                transcript,
                experience,
                session,
                new AhProxyCommand("seller", "/ah sell " + auctionId + " beacon COIN", "open-menu", now));
        require("Confirm Auction Listing".equals(sellMenu.menuView().title()), "sell command must render listing confirmation");
        require(sellMenu.receipts().isEmpty(), "sell menu render must not append an escrow command yet");

        AuctionExperienceReceipt open = submit(
                transcript,
                experience,
                session,
                AuctionMenuClick.confirmListing("seller", auctionId, "beacon", "COIN", "confirm-open", now.plusSeconds(1)));
        proxy(
                transcript,
                experience,
                session,
                new AhProxyCommand("bidder-low", "/ah browse " + auctionId, "browse-board", now.plusSeconds(2)));
        AuctionExperienceReceipt low = submit(
                transcript,
                experience,
                session,
                AuctionMenuClick.placeBid("bidder-low", auctionId, 100, "COIN", "bid-low", now.plusSeconds(3)));
        AuctionExperienceReceipt high = submit(
                transcript,
                experience,
                session,
                AuctionMenuClick.placeBid("bidder-high", auctionId, 175, "COIN", "bid-high", now.plusSeconds(4)));
        AuctionExperienceReceipt close = submit(
                transcript,
                experience,
                session,
                AuctionMenuClick.settle("seller", auctionId, "settle-close", now.plusSeconds(5)));

        List<AuctionExperienceReceipt> originalReceipts = List.of(open, low, high, close);
        for (AuctionExperienceReceipt receipt : originalReceipts) {
            AuctionEscrowLiveStoreClient.PostgresDecisionObservation decision =
                    client.awaitDecision(receipt.commandId().value(), timeout);
            AuctionEscrowLiveStoreClient.CommandAppend append = commandPort.append(receipt.commandId().value());
            require(decision.sourceTopic().equals(append.topic()), "decision source topic must match Kafka append");
            require(decision.sourceOffset() == append.offset(), "decision source offset must match Kafka append");
            require(!decision.replayed(), "first live command application must not be replayed");
            transcript.add(HeadlessAuctionBotWitness.TranscriptStep.applied(
                    WORKER,
                    receipt.commandId().value(),
                    decision.revision(),
                    false));
        }

        AuctionEscrowLiveStoreClient.ResponseObservation closeResponse =
                client.awaitResponse(close.commandId().value(), timeout);
        require("SETTLED".equals(closeResponse.fields().get("escrowStatus")), "close response must settle escrow");

        AuctionEscrowLiveStoreClient.PostgresDecisionObservation settleDecision =
                client.awaitDecision(close.commandId().value(), timeout);
        AuctionEscrowLiveStoreClient.PostgresRecordObservation record =
                client.awaitRecord(aggregateId, settleDecision.revision(), timeout);
        AuctionEscrowLiveStoreClient.CassandraProjectionObservation projection =
                client.awaitProjection(aggregateId, EscrowStatus.SETTLED, settleDecision.revision(), timeout);
        AuctionEscrowLiveStoreClient.ValkeyObservation idempotency =
                client.awaitIdempotency(close.correlationId(), timeout);

        Map<String, String> state = record.stateFields();
        long totalHeld = longField(state, "releaseTotalHeld");
        long totalPayout = longField(state, "releaseTotalPayout");
        long totalRefunded = longField(state, "releaseTotalRefunded");
        String releasePlanFingerprint = requiredField(state, "releaseFingerprint");
        require(record.revision() == projection.revision(), "PostgreSQL and Cassandra revisions must agree");
        require(totalHeld == projection.totalHeldMinor(), "PostgreSQL and Cassandra held totals must agree");
        require(totalPayout + totalRefunded == projection.totalReleasedMinor(),
                "PostgreSQL release totals must match Cassandra totalReleased");
        require("SETTLED".equals(requiredField(state, "status")), "PostgreSQL state must be settled");
        transcript.add(HeadlessAuctionBotWitness.TranscriptStep.checkpoint(
                "durable-close",
                "sourceOffset=" + settleDecision.sourceOffset()
                        + "|postgresRevision=" + record.revision()
                        + "|cassandraRevision=" + projection.revision()
                        + "|valkeyFingerprint=" + idempotency.payloadFingerprint(),
                record.revision()));

        if (options.deleteEscrowPod()) {
            KubernetesEscrowPodDisruptor.PodReplacementEvidence replacement = disruptor
                    .orElseThrow(() -> new IllegalStateException("live-store pod deletion requested without a Kubernetes disruptor"))
                    .deleteReadyPodAndAwaitReplacement();
            transcript.add(HeadlessAuctionBotWitness.TranscriptStep.checkpoint(
                    "pod-disruption",
                    replacement.transcriptResult(),
                    record.revision()));
            transcript.add(HeadlessAuctionBotWitness.TranscriptStep.restart(
                    replacement.deletedPodName() + "/" + replacement.deletedPodUid(),
                    replacement.replacementPodName() + "/" + replacement.replacementPodUid(),
                    record.revision(),
                    0));
        } else {
            transcript.add(HeadlessAuctionBotWitness.TranscriptStep.checkpoint(
                    "pod-disruption-skipped",
                    "reason=FULCRUM_WITNESS_DELETE_ESCROW_POD=false",
                    record.revision()));
        }

        AuthorityCommand<AuctionEscrowCommand> closeCommand = commandPort.command(close.commandId().value());
        AuthorityCommand<AuctionEscrowCommand> replayCommand = replayCommand(
                closeCommand,
                close.commandId().value() + ":replay",
                now.plusSeconds(6));
        AuctionEscrowLiveStoreClient.CommandAppend replayAppend = client.append(replayCommand);
        transcript.add(HeadlessAuctionBotWitness.TranscriptStep.botCommand(
                "seller",
                "settle-close-replay",
                "SETTLE_REPLAY",
                replayCommand.envelope().commandName().value(),
                "submitted"));

        AuctionEscrowLiveStoreClient.PostgresDecisionObservation replayDecision =
                client.awaitDecision(replayCommand.envelope().commandId().value(), timeout);
        require(replayDecision.replayed(), "replay probe must be recorded as a replayed close decision");
        require(replayDecision.revision() == settleDecision.revision(), "replay probe must keep the settled revision");
        require(replayDecision.sourceOffset() == replayAppend.offset(), "replay decision source offset must match Kafka append");
        client.pollResponses(Duration.ofMillis(500));
        long settleResponses = client.observedResponses().stream()
                .filter(response -> auctionId.equals(response.fields().get("auctionId")))
                .filter(response -> "SETTLED".equals(response.fields().get("escrowStatus")))
                .count();
        require(settleResponses == 1, "replay probe must not emit a second terminal response");
        require(client.observedResponses().stream()
                        .noneMatch(response -> response.commandId().equals(replayCommand.envelope().commandId().value())),
                "replay probe must not emit a response");
        transcript.add(HeadlessAuctionBotWitness.TranscriptStep.applied(
                WORKER,
                replayCommand.envelope().commandId().value(),
                replayDecision.revision(),
                true));

        HeadlessAuctionBotWitness.SettlementCertificate certificate =
                new HeadlessAuctionBotWitness.SettlementCertificate(
                        HeadlessAuctionBotWitness.SCHEMA,
                        options.executionMode(),
                        options.deleteEscrowPod(),
                        auctionId,
                        winningBidder(state),
                        totalHeld,
                        totalPayout,
                        totalRefunded,
                        releasePlanFingerprint,
                        (int) settleResponses,
                        1,
                        List.of(WORKER),
                        List.copyOf(transcript));
        certificate.assertConserved();
        return certificate;
    }

    private static KubernetesEscrowPodDisruptor disrupt(Map<String, String> environment) {
        try {
            return KubernetesEscrowPodDisruptor.fromEnvironment(environment);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to configure Kubernetes escrow pod disruptor", exception);
        }
    }

    private static AuctionExperienceResult proxy(
            List<HeadlessAuctionBotWitness.TranscriptStep> transcript,
            AuctionExperience experience,
            AuctionExperienceSession session,
            AhProxyCommand command) {
        AuctionExperienceResult result = experience.handle(session, command);
        transcript.add(HeadlessAuctionBotWitness.TranscriptStep.botCommand(
                command.playerId(),
                command.correlationId(),
                command.rawCommand(),
                result.menuView().title(),
                "rendered"));
        return result;
    }

    private static AuctionExperienceReceipt submit(
            List<HeadlessAuctionBotWitness.TranscriptStep> transcript,
            AuctionExperience experience,
            AuctionExperienceSession session,
            AuctionMenuClick click) {
        AuctionExperienceResult result = experience.handle(session, click);
        require(result.receipts().size() == 1, "menu click must submit exactly one escrow command");
        AuctionExperienceReceipt receipt = result.receipts().getFirst();
        require(AuctionEscrowContract.CONTRACT.equals(receipt.contractName()), "receipt must target auction.escrow.v1");
        transcript.add(HeadlessAuctionBotWitness.TranscriptStep.botCommand(
                click.playerId(),
                click.correlationId(),
                click.action().name(),
                receipt.commandName().value(),
                "submitted"));
        return receipt;
    }

    private static AuthorityCommand<AuctionEscrowCommand> replayCommand(
            AuthorityCommand<AuctionEscrowCommand> original,
            String commandId,
            Instant receivedAt) {
        CommandEnvelope<AuctionEscrowCommand> envelope = original.envelope();
        CommandEnvelope<AuctionEscrowCommand> replayEnvelope = new CommandEnvelope<>(
                new CommandId(commandId),
                envelope.idempotencyKey(),
                envelope.principalId(),
                envelope.aggregateId(),
                envelope.contractName(),
                envelope.commandName(),
                envelope.traceEnvelope().child("span-settle-close-replay", receivedAt),
                envelope.deadlineAt(),
                envelope.payload());
        return new AuthorityCommand<>(
                replayEnvelope,
                original.authenticatedPrincipal(),
                original.fencingEpoch(),
                Optional.of(new Revision(4)),
                original.payloadFingerprint(),
                receivedAt);
    }

    private static String winningBidder(Map<String, String> state) {
        String holds = requiredField(state, "holds");
        return List.of(holds.split(",", -1)).stream()
                .map(EncodedHold::from)
                .max(Comparator.comparingLong(EncodedHold::amountMinor))
                .map(EncodedHold::bidderId)
                .orElseThrow(() -> new IllegalStateException("settled escrow must contain at least one hold"));
    }

    private static long longField(Map<String, String> fields, String name) {
        return Long.parseLong(requiredField(fields, name));
    }

    private static String requiredField(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing escrow live-store evidence field " + name);
        }
        return value;
    }

    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record EncodedHold(String bidderId, long amountMinor) {
        static EncodedHold from(String encoded) {
            String[] fields = encoded.split("~", -1);
            if (fields.length < 3) {
                throw new IllegalStateException("invalid encoded escrow hold evidence");
            }
            return new EncodedHold(decoded(fields[1]), Long.parseLong(fields[2]));
        }
    }

    private static final class LiveAuctionCommandPort implements AuctionCommandPort {
        private final AuctionEscrowLiveStoreClient client;
        private final Map<String, AuthorityCommand<AuctionEscrowCommand>> commands = new LinkedHashMap<>();
        private final Map<String, AuctionEscrowLiveStoreClient.CommandAppend> appends = new LinkedHashMap<>();

        private LiveAuctionCommandPort(AuctionEscrowLiveStoreClient client) {
            this.client = Objects.requireNonNull(client, "client");
        }

        @Override
        public AuctionExperienceReceipt append(AuthorityCommand<AuctionEscrowCommand> command) {
            AuctionEscrowLiveStoreClient.CommandAppend append = client.append(command);
            String commandId = command.envelope().commandId().value();
            commands.put(commandId, command);
            appends.put(commandId, append);
            return new AuctionExperienceReceipt(
                    command.envelope().commandId(),
                    command.envelope().contractName(),
                    command.envelope().commandName(),
                    command.envelope().aggregateId(),
                    command.envelope().idempotencyKey().value());
        }

        private AuthorityCommand<AuctionEscrowCommand> command(String commandId) {
            AuthorityCommand<AuctionEscrowCommand> command = commands.get(commandId);
            if (command == null) {
                throw new IllegalStateException("missing live-store command " + commandId);
            }
            return command;
        }

        private AuctionEscrowLiveStoreClient.CommandAppend append(String commandId) {
            AuctionEscrowLiveStoreClient.CommandAppend append = appends.get(commandId);
            if (append == null) {
                throw new IllegalStateException("missing live-store Kafka append " + commandId);
            }
            return append;
        }
    }
}
