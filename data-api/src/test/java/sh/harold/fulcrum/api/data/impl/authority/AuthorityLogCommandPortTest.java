package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.events.AuthorityStateRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityLogCommandPortTest {
    @Test
    void acceptedCommandsAppendCommandEventStateAndResponseFrames() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(7L);
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();
        AuthorityLogCommandPort port = new AuthorityLogCommandPort(
            log,
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(acceptedResult(appliedCommand, 8L));
            }
        );

        DataAuthority.CommandResult result = port.submit(command).toCompletableFuture().join();

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        List<AuthorityLogRecord> commands = log.records(route.commandTopic(), partition);
        List<AuthorityLogRecord> events = log.records(route.eventTopic(), partition);
        List<AuthorityLogRecord> states = log.records(route.stateTopic(), partition);
        List<AuthorityLogRecord> responses = log.records(route.responseTopic(), partition);

        assertThat(result.accepted()).isTrue();
        assertThat(applied.get()).isNotSameAs(command);
        assertThat(applied.get().commandId()).isEqualTo(command.commandId());
        assertThat(AuthorityCommandPayloads.payload(applied.get())).isEqualTo(AuthorityCommandPayloads.payload(command));
        assertThat(result.settlement().watermark().logPositioned()).isTrue();
        assertThat(commands).singleElement().satisfies(record -> {
            assertThat(record.kind()).isEqualTo(AuthorityLogTopicKind.COMMAND);
            assertThat(record.key()).isEqualTo(command.scope());
            assertThat(record.payload())
                .containsEntry("frameType", "COMMAND")
                .containsEntry("declarationId", declarationId(command))
                .containsKey("commandFingerprint")
                .doesNotContainKey("commandType");
        });
        assertThat(events).singleElement().satisfies(record -> {
            assertThat(record.kind()).isEqualTo(AuthorityLogTopicKind.EVENT);
            assertThat(record.payload())
                .containsEntry("frameType", "EVENT")
                .containsEntry("declarationId", declarationId(command))
                .containsEntry("revision", result.revision())
                .containsEntry("aggregateScope", command.scope())
                .containsEntry("sourceCommandOffset", commands.get(0).offset())
                .doesNotContainKey("commandType");
        });
        AuthorityLogRecord stateRecord = states.get(0);
        assertThat(stateRecord.kind()).isEqualTo(AuthorityLogTopicKind.STATE);
        assertThat(stateRecord.payload())
            .containsEntry("frameType", "STATE")
            .containsEntry("revision", result.revision())
            .containsEntry("stateFingerprint", result.settlement().watermark().stateFingerprint())
            .containsEntry("statePayload", result.settlement().statePayload())
            .containsEntry("sourceCommandPartition", commands.get(0).partition())
            .containsEntry("sourceCommandOffset", commands.get(0).offset());
        AuthorityStateRecord parsedState = AuthorityStateRecord.fromLogRecord(stateRecord);
        assertThat(parsedState.aggregateScope()).isEqualTo(command.scope());
        assertThat(parsedState.statePayload()).isEqualTo(result.settlement().statePayload());
        assertThat(parsedState.hasValidStateFingerprint()).isTrue();
        assertThat(parsedState.sourcePartition()).isEqualTo(stateRecord.partition());
        assertThat(parsedState.sourceOffset()).isEqualTo(stateRecord.offset());
        assertThat(log.compacted(route.stateTopic())).containsEntry(route.partitionKey(), stateRecord);

        assertThat(responses).singleElement().satisfies(record -> {
            assertThat(record.kind()).isEqualTo(AuthorityLogTopicKind.RESPONSE);
            assertThat(record.payload())
                .containsEntry("frameType", "RESPONSE")
                .containsEntry("declarationId", declarationId(command))
                .containsEntry("accepted", true)
                .containsEntry("revision", result.revision())
                .containsEntry("sourceCommandTopic", commands.get(0).topic())
                .containsEntry("sourceCommandPartition", commands.get(0).partition())
                .containsEntry("sourceCommandOffset", commands.get(0).offset())
                .doesNotContainKey("commandType");
            Map<?, ?> settlement = map(record.payload().get("settlement"));
            Map<?, ?> watermark = map(settlement.get("watermark"));
            assertThat(watermark.get("sourcePartition")).isEqualTo(stateRecord.partition());
            assertThat(watermark.get("sourceOffset")).isEqualTo(stateRecord.offset());
        });
        assertThat(result.settlement().watermark().sourcePartition()).isEqualTo(stateRecord.partition());
        assertThat(result.settlement().watermark().sourceOffset()).isEqualTo(stateRecord.offset());
    }

    @Test
    void workerProcessesAlreadyDurableCommandRecords() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(9L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AuthorityWriterClaim claim = claimFor(command, "authority-rank-1", 1L);
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(acceptedResult(appliedCommand, 10L));
            }
        );

        AuthorityLogCommandProcessor.ProcessingResult processed =
            processor.process(commandRecord, claim).toCompletableFuture().join();

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        List<AuthorityLogRecord> commands = log.records(route.commandTopic(), partition);
        List<AuthorityLogRecord> events = log.records(route.eventTopic(), partition);
        List<AuthorityLogRecord> states = log.records(route.stateTopic(), partition);
        List<AuthorityLogRecord> responses = log.records(route.responseTopic(), partition);

        assertThat(applied.get()).isNotNull();
        assertThat(applied.get().commandId()).isEqualTo(command.commandId());
        assertThat(AuthorityCommandPayloads.payload(applied.get())).isEqualTo(AuthorityCommandPayloads.payload(command));
        assertThat(processed.commandRecord()).isEqualTo(commandRecord);
        assertThat(processed.commandResult().accepted()).isTrue();
        assertThat(commands).containsExactly(commandRecord);
        assertThat(events).hasSize(1);
        assertThat(states).hasSize(1);
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.payload())
                .containsEntry("frameType", "RESPONSE")
                .containsEntry("commandId", command.commandId().toString())
                .containsEntry("accepted", true)
                .containsEntry("writerClaimId", claim.claimId().toString())
                .containsEntry("sourceCommandPartition", commandRecord.partition())
                .containsEntry("sourceCommandOffset", commandRecord.offset());
        });
        assertThat(processed.commandResult().settlement().watermark().sourcePartition())
            .isEqualTo(states.get(0).partition());
        assertThat(processed.commandResult().settlement().watermark().sourceOffset())
            .isEqualTo(states.get(0).offset());
    }

    @Test
    void processorRejectsCommandRecordWithoutWriterClaim() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(13L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            ignored -> {
                throw new AssertionError("unclaimed command record should not be applied");
            }
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                processor.process(commandRecord).toCompletableFuture().join()
            )
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires a writer claim");
    }

    @Test
    void logClientSubmitsCommandAndWaitsForCorrelatedResponseRecord() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(21L);
        AuthorityLogDataAuthorityClient client = new AuthorityLogDataAuthorityClient(
            log,
            Duration.ofSeconds(2),
            transportProvenance()
        );
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);

        CompletableFuture<DataAuthority.CommandResult> pending = client.submit(command).toCompletableFuture();
        AuthorityLogRecord commandRecord = log.records(route.commandTopic(), partition).get(0);
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            appliedCommand -> CompletableFuture.completedFuture(acceptedResult(appliedCommand, 22L))
        );

        processor.process(commandRecord, claimFor(command, "authority-rank-1", 1L)).toCompletableFuture().join();
        DataAuthority.CommandResult result = pending.join();

        assertThat(result.accepted()).isTrue();
        assertThat(result.commandId()).isEqualTo(command.commandId());
        assertThat(result.revision()).isEqualTo(22L);
        assertThat(result.settlement().responseTopic()).isEqualTo(route.responseTopic());
        assertThat(result.settlement().watermark().logPositioned()).isTrue();
        assertThat(log.records(route.responseTopic(), partition)).singleElement().satisfies(response ->
            assertThat(response.payload())
                .containsEntry("commandId", command.commandId().toString())
                .containsEntry("accepted", true)
        );
    }

    @Test
    void logClientDurableSubmissionReturnsCommandLogPositionWithoutAwaitingResponse() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(23L);
        AuthorityLogDataAuthorityClient client = new AuthorityLogDataAuthorityClient(
            log,
            Duration.ofSeconds(2),
            transportProvenance()
        );
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);

        DataAuthority.CommandSubmissionReceipt receipt =
            client.submitDurable(command).toCompletableFuture().join();

        assertThat(receipt.commandId()).isEqualTo(command.commandId());
        assertThat(receipt.declarationId()).isEqualTo(command.declarationId());
        assertThat(receipt.aggregateScope()).isEqualTo(command.scope());
        assertThat(receipt.commandDomain()).isEqualTo(route.domain());
        assertThat(receipt.commandTopic()).isEqualTo(route.commandTopic());
        assertThat(receipt.responseTopic()).isEqualTo(route.responseTopic());
        assertThat(receipt.partitionKey()).isEqualTo(route.partitionKey());
        assertThat(receipt.partition()).isEqualTo(partition);
        assertThat(receipt.offset()).isZero();
        assertThat(log.records(route.commandTopic(), partition)).singleElement().satisfies(record ->
            assertThat(record.payload())
                .containsEntry("commandId", command.commandId().toString())
                .containsEntry("frameType", "COMMAND")
        );
        assertThat(log.records(route.responseTopic(), partition)).isEmpty();
    }

    @Test
    void logClientRequiresVerifiedTransportProvenance() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AuthorityLogDataAuthorityClient(
            log,
            Duration.ofSeconds(2),
            DataAuthority.CommandProvenance.unknown()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verified node principal");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AuthorityLogDataAuthorityClient(
            log,
            Duration.ofSeconds(2),
            new DataAuthority.CommandProvenance(
                "paper-7",
                "kafka:paper-7->registry-service",
                AuthorityPrincipalCommandPort.KAFKA_COMMAND_LOG_PROVIDER,
                DataAuthority.COMMAND_SCHEMA_VERSION,
                "rank-service"
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verified node principal");
    }

    @Test
    void logClientStampsTransportProvenanceIntoCommandFrame() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.CommandProvenance provenance = transportProvenance();
        DataAuthority.PlayerRankCommand command = rankCommand(
            24L,
            "rank-service",
            DataAuthority.CommandProvenance.unknown()
        );
        AuthorityLogDataAuthorityClient client = new AuthorityLogDataAuthorityClient(
            log,
            Duration.ofSeconds(2),
            provenance
        );

        DataAuthority.CommandSubmissionReceipt receipt = client.submitDurable(command).toCompletableFuture().join();

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        assertThat(receipt.provenance()).isEqualTo(provenance);
        assertThat(log.records(route.commandTopic(), partition)).singleElement().satisfies(record -> {
            assertThat(record.payload())
                .containsEntry("actorId", "node:paper-7")
                .containsEntry("verifiedPrincipal", "node:paper-7");
            Map<?, ?> manifest = map(record.payload().get("manifest"));
            assertThat(manifest.get("actorId")).isEqualTo("node:paper-7");
            Map<?, ?> stampedProvenance = map(manifest.get("provenance"));
            assertThat(stampedProvenance.get("originNode")).isEqualTo("paper-7");
            assertThat(stampedProvenance.get("authorityRoute")).isEqualTo("kafka:paper-7->registry-service");
            assertThat(stampedProvenance.get("providerKind"))
                .isEqualTo(AuthorityPrincipalCommandPort.KAFKA_COMMAND_LOG_PROVIDER);
            assertThat(stampedProvenance.get("verifiedPrincipal")).isEqualTo("node:paper-7");
        });
    }

    @Test
    void processorRejectsReservedActorMismatchBeforeApplying() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(
            25L,
            "node:paper-2",
            new DataAuthority.CommandProvenance(
                "paper-1",
                "kafka:paper-1->registry-service",
                AuthorityPrincipalCommandPort.KAFKA_COMMAND_LOG_PROVIDER,
                DataAuthority.COMMAND_SCHEMA_VERSION,
                "node:paper-1"
            )
        );
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            ignored -> {
                delegated.set(true);
                return CompletableFuture.completedFuture(acceptedResult(command, 26L));
            }
        );

        AuthorityLogCommandProcessor.ProcessingResult processed = processor
            .process(commandRecord, claimFor(command, "authority-rank-1", 1L))
            .toCompletableFuture()
            .join();

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        assertThat(delegated).isFalse();
        assertThat(processed.commandResult().accepted()).isFalse();
        assertThat(processed.commandResult().rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(log.records(route.eventTopic(), partition)).isEmpty();
        assertThat(log.records(route.stateTopic(), partition)).isEmpty();
        assertThat(log.records(route.responseTopic(), partition)).singleElement().satisfies(response ->
            assertThat(response.payload())
                .containsEntry("accepted", false)
                .containsEntry("rejectionReason", DataAuthority.RejectionReason.INVALID_ACTOR.name())
                .containsEntry("sourceCommandOffset", commandRecord.offset())
        );
    }

    @Test
    void partitionWorkerClaimsAuthorityLaneBeforeApplyingDurableRecords() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(13L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        AtomicReference<AuthorityWriterClaim> issuedClaim = new AtomicReference<>();
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();
        AtomicReference<String> claimedPartitionKey = new AtomicReference<>();

        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(acceptedResult(appliedCommand, 14L));
            },
            (commandDomain, commandTopic, partitionKey, ownerNode) -> {
                claimedPartitionKey.set(partitionKey);
                AuthorityWriterClaim claim = AuthorityWriterClaim.mint(
                    commandDomain,
                    commandTopic,
                    partitionKey,
                    ownerNode,
                    3L,
                    null,
                    0L,
                    Instant.EPOCH
                );
                issuedClaim.set(claim);
                return claim;
            },
            "authority-rank-1"
        );

        AuthorityLogCommandWorker.PartitionResult result =
            worker.processPartition("rank", partition, -1L, 10).toCompletableFuture().join();

        assertThat(result.domain()).isEqualTo("rank");
        assertThat(result.commandTopic()).isEqualTo(route.commandTopic());
        assertThat(result.partition()).isEqualTo(partition);
        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.lastProcessedOffset()).isEqualTo(commandRecord.offset());
        assertThat(result.writerClaim()).isEqualTo(issuedClaim.get());
        assertThat(claimedPartitionKey.get()).isEqualTo("rank:lane:" + partition);
        AuthorityWriterClaimToken token = AuthorityWriterClaimToken.parse(applied.get().fencingToken());
        assertThat(token).isNotNull();
        assertThat(token.epoch()).isEqualTo(3L);
        assertThat(token.claimId()).isEqualTo(issuedClaim.get().claimId());
        assertThat(log.records(route.responseTopic(), partition)).singleElement().satisfies(response ->
            assertThat(response.payload())
                .containsEntry("writerClaimEpoch", 3L)
                .containsEntry("writerClaimPartitionKey", "rank:lane:" + partition)
                .containsEntry("sourceCommandOffset", commandRecord.offset())
        );
    }

    @Test
    void domainScopedCommandPortDelegatesMatchingDomain() {
        DataAuthority.PlayerRankCommand command = rankCommand(14L);
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();
        AuthorityDomainScopedCommandPort port = new AuthorityDomainScopedCommandPort(
            "rank",
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(acceptedResult(appliedCommand, 15L));
            }
        );

        DataAuthority.CommandResult result = port.submit(command).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(applied.get()).isEqualTo(command);
    }

    @Test
    void partitionWorkerRejectsCommandsOutsideMaterializedDelegateDomain() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(16L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        AtomicBoolean applied = new AtomicBoolean(false);

        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            new AuthorityDomainScopedCommandPort(
                "player",
                ignored -> {
                    applied.set(true);
                    return CompletableFuture.completedFuture(acceptedResult(command, 17L));
                }
            ),
            (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                commandDomain,
                commandTopic,
                partitionKey,
                ownerNode,
                6L,
                null,
                0L,
                Instant.EPOCH
            ),
            "authority-rank-1"
        );

        AuthorityLogCommandWorker.PartitionResult result =
            worker.processPartition("rank", partition, -1L, 10).toCompletableFuture().join();

        assertThat(applied.get()).isFalse();
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.lastProcessedOffset()).isEqualTo(commandRecord.offset());
        assertThat(result.processed()).singleElement().satisfies(processed -> {
            assertThat(processed.commandResult().accepted()).isFalse();
            assertThat(processed.commandResult().rejectionReason())
                .isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED);
        });
        assertThat(log.records(route.eventTopic(), partition)).isEmpty();
        assertThat(log.records(route.stateTopic(), partition)).isEmpty();
        assertThat(log.records(route.responseTopic(), partition)).singleElement().satisfies(response ->
            assertThat(response.payload())
                .containsEntry("accepted", false)
                .containsEntry("rejectionReason", DataAuthority.RejectionReason.VALIDATION_FAILED.name())
                .containsEntry("sourceCommandOffset", commandRecord.offset())
        );
    }

    @Test
    void partitionWorkerResumesAfterLastProcessedOffset() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand first = rankCommand(15L);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(first);
        AuthorityLogRecord firstRecord = AuthorityLogFrames.appendCommand(log, first);
        DataAuthority.PlayerRankCommand second = rankCommandForScope(16L, first.playerId(), first.scope());
        AuthorityLogRecord secondRecord = AuthorityLogFrames.appendCommand(log, second);
        int partition = AuthorityLogTopology.partition(route);
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();

        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(acceptedResult(appliedCommand, 17L));
            },
            (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                commandDomain,
                commandTopic,
                partitionKey,
                ownerNode,
                4L,
                null,
                0L,
                Instant.EPOCH
            ),
            "authority-rank-1"
        );

        AuthorityLogCommandWorker.PartitionResult result =
            worker.processPartition("rank", partition, firstRecord.offset(), 10).toCompletableFuture().join();

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.lastProcessedOffset()).isEqualTo(secondRecord.offset());
        assertThat(applied.get().commandId()).isEqualTo(second.commandId());
    }

    @Test
    void workerLoopPollsAssignedPartitionsFromCommittedOffsets() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand first = rankCommand(18L);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(first);
        int partition = AuthorityLogTopology.partition(route);
        AuthorityLogRecord firstRecord = AuthorityLogFrames.appendCommand(log, first);
        DataAuthority.PlayerRankCommand second = rankCommandForScope(19L, first.playerId(), first.scope());
        AuthorityLogRecord secondRecord = AuthorityLogFrames.appendCommand(log, second);
        AtomicInteger appliedCount = new AtomicInteger();

        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            appliedCommand -> CompletableFuture.completedFuture(acceptedResult(
                appliedCommand,
                20L + appliedCount.incrementAndGet()
            )),
            (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                commandDomain,
                commandTopic,
                partitionKey,
                ownerNode,
                5L,
                null,
                0L,
                Instant.EPOCH
            ),
            "authority-rank-1"
        );
        AuthorityLogCommandWorkerLoop loop = new AuthorityLogCommandWorkerLoop(
            worker,
            "rank",
            List.of(partition),
            1
        );

        AuthorityLogCommandWorkerLoop.PollResult firstPoll = loop.pollOnce().toCompletableFuture().join();
        AuthorityLogCommandWorkerLoop.PollResult secondPoll = loop.pollOnce().toCompletableFuture().join();
        AuthorityLogCommandWorkerLoop.PollResult emptyPoll = loop.pollOnce().toCompletableFuture().join();

        assertThat(firstPoll.processedCount()).isEqualTo(1);
        assertThat(firstPoll.partitions().get(partition).lastProcessedOffset()).isEqualTo(firstRecord.offset());
        assertThat(secondPoll.processedCount()).isEqualTo(1);
        assertThat(secondPoll.partitions().get(partition).lastProcessedOffset()).isEqualTo(secondRecord.offset());
        assertThat(emptyPoll.processedCount()).isZero();
        assertThat(loop.committedOffset(partition)).isEqualTo(secondRecord.offset());
        assertThat(appliedCount.get()).isEqualTo(2);
    }

    @Test
    void workerLoopResumesFromSharedCommandConsumerCursorStore() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand first = rankCommand(23L);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(first);
        int partition = AuthorityLogTopology.partition(route);
        AuthorityLogRecord firstRecord = AuthorityLogFrames.appendCommand(log, first);
        DataAuthority.PlayerRankCommand second = rankCommandForScope(24L, first.playerId(), first.scope());
        AuthorityLogRecord secondRecord = AuthorityLogFrames.appendCommand(log, second);
        AtomicInteger appliedCount = new AtomicInteger();
        InMemoryAuthorityCommandConsumerCursorStore cursorStore =
            new InMemoryAuthorityCommandConsumerCursorStore();

        AuthorityLogCommandWorker worker = new AuthorityLogCommandWorker(
            log,
            appliedCommand -> CompletableFuture.completedFuture(acceptedResult(
                appliedCommand,
                30L + appliedCount.incrementAndGet()
            )),
            (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                commandDomain,
                commandTopic,
                partitionKey,
                ownerNode,
                6L,
                null,
                0L,
                Instant.EPOCH
            ),
            "authority-rank-1"
        );
        AuthorityLogCommandWorkerLoop firstLoop = new AuthorityLogCommandWorkerLoop(
            worker,
            "rank",
            List.of(partition),
            1,
            cursorStore
        );

        AuthorityLogCommandWorkerLoop.PollResult firstPoll = firstLoop.pollOnce().toCompletableFuture().join();

        assertThat(firstPoll.processedCount()).isEqualTo(1);
        assertThat(firstLoop.committedOffset(partition)).isEqualTo(firstRecord.offset());

        AuthorityLogCommandWorkerLoop secondLoop = new AuthorityLogCommandWorkerLoop(
            worker,
            "rank",
            List.of(partition),
            1,
            cursorStore
        );
        AuthorityLogCommandWorkerLoop.PollResult secondPoll = secondLoop.pollOnce().toCompletableFuture().join();

        assertThat(secondPoll.processedCount()).isEqualTo(1);
        assertThat(secondPoll.partitions().get(partition).lastProcessedOffset()).isEqualTo(secondRecord.offset());
        assertThat(secondLoop.committedOffset(partition)).isEqualTo(secondRecord.offset());
        assertThat(cursorStore.cursor("rank", partition)).hasValueSatisfying(cursor -> {
            assertThat(cursor.commandTopic()).isEqualTo(route.commandTopic());
            assertThat(cursor.committedOffset()).isEqualTo(secondRecord.offset());
            assertThat(cursor.lastCommandId()).isEqualTo(second.commandId());
            assertThat(cursor.writerClaimEpoch()).isEqualTo(6L);
        });
        assertThat(appliedCount.get()).isEqualTo(2);
    }

    @Test
    void claimedWorkerStampsFencingAndClaimEvidenceBeforeApplying() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(10L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AuthorityWriterClaim claim = claimFor(command, "authority-rank-1", 12L);
        AtomicReference<DataAuthority.AuthorityCommand> applied = new AtomicReference<>();
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            appliedCommand -> {
                applied.set(appliedCommand);
                return CompletableFuture.completedFuture(acceptedResult(appliedCommand, 11L));
            }
        );

        AuthorityLogCommandProcessor.ProcessingResult processed =
            processor.process(commandRecord, claim).toCompletableFuture().join();

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        List<AuthorityLogRecord> responses = log.records(route.responseTopic(), partition);

        assertThat(processed.commandResult().accepted()).isTrue();
        assertThat(applied.get().fencingToken()).isEqualTo(claim.fencingToken());
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.payload())
                .containsEntry("sourceCommandOffset", commandRecord.offset())
                .containsEntry("writerClaimEpoch", claim.epoch())
                .containsEntry("writerClaimId", claim.claimId().toString())
                .containsEntry("writerClaimOwnerNode", claim.ownerNode())
                .containsEntry("writerClaimPartitionKey", claim.partitionKey())
                .containsEntry("writerClaimFingerprint", claim.claimFingerprint());
        });
    }

    @Test
    void claimedWorkerRejectsWrongAuthorityLaneBeforeApplying() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(12L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        AuthorityWriterClaim wrongLane = AuthorityWriterClaim.mint(
            route.domain(),
            route.commandTopic(),
            "rank:lane:999",
            "authority-rank-1",
            12L,
            null,
            0L,
            Instant.EPOCH
        );
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            ignored -> {
                delegated.set(true);
                throw new AssertionError("wrong-lane claim should not be applied");
            }
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                processor.process(commandRecord, wrongLane).toCompletableFuture().join()
            )
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("claim partition key");

        assertThat(delegated).isFalse();
        int partition = AuthorityLogTopology.partition(route);
        assertThat(log.records(route.eventTopic(), partition)).isEmpty();
        assertThat(log.records(route.stateTopic(), partition)).isEmpty();
        assertThat(log.records(route.responseTopic(), partition)).isEmpty();
    }

    @Test
    void workerRejectsPartitionMismatchedCommandRecordsBeforeApplying() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(11L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        AuthorityLogRecord tampered = new AuthorityLogRecord(
            commandRecord.topic(),
            commandRecord.key(),
            commandRecord.partition() + 1,
            commandRecord.offset(),
            commandRecord.kind(),
            commandRecord.payload(),
            commandRecord.headers(),
            commandRecord.appendedAtEpochMillis()
        );
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            ignored -> {
                throw new AssertionError("mismatched command record should not be applied");
            }
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                processor.process(tampered, claimFor(command, "authority-rank-1", 1L)).toCompletableFuture().join()
            )
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match route partition");

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        assertThat(log.records(route.eventTopic(), partition)).isEmpty();
        assertThat(log.records(route.stateTopic(), partition)).isEmpty();
        assertThat(log.records(route.responseTopic(), partition)).isEmpty();
    }

    @Test
    void workerRejectsTopologyFingerprintDriftBeforeApplying() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(11L);
        AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
        Map<String, Object> payload = new java.util.LinkedHashMap<>(commandRecord.payload());
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        map(payload.get("manifest")).forEach((key, value) -> manifest.put(key.toString(), value));
        manifest.put(
            "authorityDomainTopologyFingerprint",
            "0000000000000000000000000000000000000000000000000000000000000000"
        );
        payload.put("manifest", Map.copyOf(manifest));
        AuthorityLogRecord tampered = new AuthorityLogRecord(
            commandRecord.topic(),
            commandRecord.key(),
            commandRecord.partition(),
            commandRecord.offset(),
            commandRecord.kind(),
            Map.copyOf(payload),
            commandRecord.headers(),
            commandRecord.appendedAtEpochMillis()
        );
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(
            log,
            ignored -> {
                throw new AssertionError("topology-mismatched command record should not be applied");
            }
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                processor.process(tampered, claimFor(command, "authority-rank-1", 1L)).toCompletableFuture().join()
            )
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("authorityDomainTopologyFingerprint");

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        assertThat(log.records(route.eventTopic(), partition)).isEmpty();
        assertThat(log.records(route.stateTopic(), partition)).isEmpty();
        assertThat(log.records(route.responseTopic(), partition)).isEmpty();
    }

    @Test
    void rejectedCommandsAppendCommandAndResponseOnly() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        DataAuthority.PlayerRankCommand command = rankCommand(3L);
        AuthorityLogCommandPort port = new AuthorityLogCommandPort(
            log,
            ignored -> CompletableFuture.completedFuture(rejectedResult(command))
        );

        DataAuthority.CommandResult result = port.submit(command).toCompletableFuture().join();

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        int partition = AuthorityLogTopology.partition(route);
        assertThat(result.accepted()).isFalse();
        List<AuthorityLogRecord> commands = log.records(route.commandTopic(), partition);
        assertThat(commands).hasSize(1);
        assertThat(log.records(route.responseTopic(), partition)).singleElement().satisfies(record -> {
            assertThat(record.payload())
                .containsEntry("frameType", "RESPONSE")
                .containsEntry("declarationId", declarationId(command))
                .containsEntry("accepted", false)
                .containsEntry("rejectionReason", DataAuthority.RejectionReason.STALE_REVISION.name())
                .containsEntry("sourceCommandTopic", commands.get(0).topic())
                .containsEntry("sourceCommandOffset", commands.get(0).offset())
                .containsKey("refusalReceipt")
                .doesNotContainKey("commandType");
            Map<?, ?> refusalReceipt = map(record.payload().get("refusalReceipt"));
            assertThat(refusalReceipt.get("declarationId")).isEqualTo(declarationId(command));
            assertThat(refusalReceipt.containsKey("commandType")).isFalse();
        });
        assertThat(log.records(route.eventTopic(), partition)).isEmpty();
        assertThat(log.records(route.stateTopic(), partition)).isEmpty();
    }

    @Test
    void validateSchemaRequiresAllDeclaredAuthorityTopics() {
        AuthorityLogCommandPort port = new AuthorityLogCommandPort(new MissingTopicLog(), command ->
            CompletableFuture.completedFuture(rejectedResult(command))
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(port::validateSchema)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing authority log topic policy");
    }

    private static DataAuthority.PlayerRankCommand rankCommand(long expectedRevision) {
        UUID playerId = UUID.randomUUID();
        return rankCommandForScope(expectedRevision, playerId, "rank:player:" + playerId);
    }

    private static DataAuthority.CommandProvenance transportProvenance() {
        return new DataAuthority.CommandProvenance(
            "paper-7",
            "kafka:paper-7->registry-service",
            AuthorityPrincipalCommandPort.KAFKA_COMMAND_LOG_PROVIDER,
            DataAuthority.COMMAND_SCHEMA_VERSION,
            "node:paper-7"
        );
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        long expectedRevision,
        String actorId,
        DataAuthority.CommandProvenance provenance
    ) {
        UUID playerId = UUID.randomUUID();
        return rankCommandForScope(expectedRevision, playerId, "rank:player:" + playerId, actorId, provenance);
    }

    private static DataAuthority.PlayerRankCommand rankCommandForScope(
        long expectedRevision,
        UUID playerId,
        String scope
    ) {
        return rankCommandForScope(
            expectedRevision,
            playerId,
            scope,
            "node:paper-1",
            new DataAuthority.CommandProvenance(
                "paper-1",
                "authority-rank",
                "messagebus",
                DataAuthority.COMMAND_SCHEMA_VERSION,
                "node:paper-1"
            )
        );
    }

    private static DataAuthority.PlayerRankCommand rankCommandForScope(
        long expectedRevision,
        UUID playerId,
        String scope,
        String actorId,
        DataAuthority.CommandProvenance provenance
    ) {
        DataAuthority.CommandManifest manifest = new DataAuthority.CommandManifest(
            UUID.randomUUID(),
            "GRANT_RANK",
            actorId,
            scope,
            "rank-grant:" + playerId + ":" + expectedRevision,
            System.currentTimeMillis() + 5_000L,
            "",
            expectedRevision,
            DataAuthority.COMMAND_SCHEMA_VERSION,
            provenance
        );
        return new DataAuthority.PlayerRankCommand(
            manifest,
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }

    private static AuthorityWriterClaim claimFor(
        DataAuthority.AuthorityCommand command,
        String ownerNode,
        long epoch
    ) {
        AuthorityWriteCustody custody = AuthorityWriteCustody.fromCommand(command);
        return AuthorityWriterClaim.mint(
            custody.commandDomain(),
            custody.commandTopic(),
            custody.ownershipPartitionKey(),
            ownerNode,
            epoch,
            null,
            0L,
            Instant.EPOCH
        );
    }

    private static DataAuthority.CommandResult acceptedResult(
        DataAuthority.AuthorityCommand command,
        long revision
    ) {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        Map<String, Object> statePayload = Map.of(
            "playerId", AuthorityCommandPayloads.payload(command).get("playerId").toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN"),
            "revision", revision
        );
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            command.scope(),
            "player_rank",
            AuthorityCommandPayloads.payload(command).get("playerId").toString(),
            route.domain(),
            route.stateTopic(),
            route.partitionKey(),
            command.commandId(),
            UUID.randomUUID(),
            revision,
            System.currentTimeMillis(),
            -1,
            -1L,
            AuthorityStateRecord.stateFingerprint(statePayload),
            hex('a')
        );
        DataAuthority.CommandSettlement settlement = new DataAuthority.CommandSettlement(
            "postgres-authority-state",
            route.domain(),
            route.commandTopic(),
            route.responseTopic(),
            route.eventTopic(),
            route.stateTopic(),
            route.partitionKey(),
            "authority-rank:0:" + UUID.randomUUID(),
            command.idempotencyKey(),
            command.expectedRevision(),
            watermark,
            statePayload
        );
        return new DataAuthority.CommandResult(
            command.commandId(),
            true,
            revision,
            DataAuthority.RejectionReason.NONE,
            "applied",
            settlement
        );
    }

    private static DataAuthority.CommandResult rejectedResult(DataAuthority.AuthorityCommand command) {
        DataAuthority.CommandRefusalReceipt receipt = DataAuthority.CommandRefusalReceipt.create(
            "authority-log",
            command.commandId(),
            command.declarationId(),
            command.scope(),
            command.provenance().originNode(),
            "authority-rank",
            AuthorityCommandRoute.fromCommand(command).commandTopic(),
            DataAuthority.RejectionReason.STALE_REVISION,
            command.expectedRevision(),
            AuthorityCommandManifest.fingerprint(),
            AuthorityCommandManifest.routeManifestFingerprint(),
            DataAuthority.CommandRefusalReceipt.payloadHash(AuthorityCommandPayloads.payload(command)),
            System.currentTimeMillis()
        );
        return new DataAuthority.CommandResult(
            command.commandId(),
            false,
            command.expectedRevision(),
            DataAuthority.RejectionReason.STALE_REVISION,
            "stale revision"
        ).withRefusalReceipt(receipt);
    }

    private static String declarationId(DataAuthority.AuthorityCommand command) {
        return AuthorityCommandManifest.declaration(command.declarationId()).declarationId();
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String hex(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static final class MissingTopicLog implements AuthorityLog {
        @Override
        public AuthorityLogRecord append(
            AuthorityCommandRoute route,
            AuthorityLogTopicKind kind,
            Map<String, Object> payload
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, AuthorityLogTopicPolicy> policiesByTopic() {
            return Map.of();
        }
    }
}
