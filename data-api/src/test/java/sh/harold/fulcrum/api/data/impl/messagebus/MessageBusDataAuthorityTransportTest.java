package sh.harold.fulcrum.api.data.impl.messagebus;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityFencingCommandPort;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityPrincipalCommandPort;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotInvalidation;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityWriterClaim;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.InMemoryMessageBus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageBusDataAuthorityTransportTest {
    @Test
    void commandClientSubmitsTypedCommandThroughProvider() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            commandId,
            eventId,
            9L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.CommandSettlement settlement = new DataAuthority.CommandSettlement(
            "postgres-authority-state",
            "player_rank",
            "cmd.player_rank",
            "evt.player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            "12",
            "rank:" + commandId,
            8L,
            watermark
        );
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        DataAuthority.CommandPort commandPort = command -> {
            received.set(command);
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                9L,
                DataAuthority.RejectionReason.NONE,
                "accepted",
                settlement
            ));
        };
        commandPort = new AuthorityPrincipalCommandPort(commandPort);

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        DataAuthority.CommandResult result = client.submit(new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000,
                "3",
                8L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(result.revision()).isEqualTo(9L);
        assertThat(result.settlement()).isEqualTo(settlement);
        assertThat(result.settlement().settled()).isTrue();
        assertThat(received.get()).isInstanceOf(DataAuthority.PlayerRankCommand.class);
        assertThat(received.get().actorId()).isEqualTo("node:authority-1");
        assertThat(received.get().expectedRevision()).isEqualTo(8L);
        assertThat(received.get().provenance().originNode()).isEqualTo("authority-1");
        assertThat(received.get().provenance().authorityRoute()).isEqualTo("messagebus:authority-1->authority-1");
        assertThat(received.get().provenance().providerKind()).isEqualTo("message-bus-provider");
        assertThat(received.get().provenance().verifiedPrincipal()).isEqualTo("node:authority-1");
        assertThat(received.get().payload()).containsEntry("primaryRank", "ADMIN");
    }

    @Test
    void commandProviderRejectsContractFingerprintMismatchWithoutDelegating() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        DataAuthority.CommandPort commandPort = command -> {
            received.set(command);
            return CompletableFuture.completedFuture(acceptedResult(command, 1L));
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("commandId", commandId.toString());
        wire.put("commandType", DataAuthority.CommandType.GRANT_RANK.name());
        wire.put("actorId", "rank-service");
        wire.put("scope", "rank:player:" + playerId);
        wire.put("idempotencyKey", "rank:" + commandId);
        wire.put("deadlineEpochMillis", System.currentTimeMillis() + 1000);
        wire.put("fencingToken", "3");
        wire.put("expectedRevision", DataAuthority.ANY_REVISION);
        wire.put("schemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION);
        wire.put("contractFingerprint", "0000000000000000000000000000000000000000000000000000000000000000");
        wire.put("payload", Map.of(
            "playerId", playerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN")
        ));

        Object rawResponse = bus.request(
            "authority-1",
            MessageBusAuthorityChannels.COMMAND,
            Map.copyOf(wire),
            Duration.ofSeconds(1)
        ).toCompletableFuture().join();
        Map<?, ?> response = (Map<?, ?>) rawResponse;

        assertThat(response.get("commandId")).isEqualTo(commandId.toString());
        assertThat(response.get("accepted")).isEqualTo(false);
        assertThat(response.get("rejectionReason")).isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED.name());
        assertThat(response.get("message").toString())
            .contains("contract fingerprint mismatch")
            .contains(DataAuthorityCommandContracts.fingerprint().substring(0, 12));
        assertThat(received.get()).isNull();
    }

    @Test
    void commandProviderRejectsRouteManifestFingerprintMismatchWithoutDelegating() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        AtomicReference<MessageBusDataAuthorityProvider.CommandRefusal> refusal =
            new AtomicReference<>();
        DataAuthority.CommandPort commandPort = command -> {
            received.set(command);
            return CompletableFuture.completedFuture(acceptedResult(command, 1L));
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            refusal::set
        );
        provider.start();

        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("commandId", commandId.toString());
        wire.put("commandType", DataAuthority.CommandType.GRANT_RANK.name());
        wire.put("actorId", "rank-service");
        wire.put("scope", "rank:player:" + playerId);
        wire.put("idempotencyKey", "rank:" + commandId);
        wire.put("deadlineEpochMillis", System.currentTimeMillis() + 1000);
        wire.put("fencingToken", "3");
        wire.put("expectedRevision", DataAuthority.ANY_REVISION);
        wire.put("schemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION);
        wire.put("contractFingerprint", DataAuthorityCommandContracts.fingerprint());
        wire.put("routeManifestFingerprint", "0000000000000000000000000000000000000000000000000000000000000000");
        wire.put("route", rankRoute(playerId));
        wire.put("payload", Map.of(
            "playerId", playerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN")
        ));

        Object rawResponse = bus.request(
            "authority-1",
            MessageBusAuthorityChannels.COMMAND,
            Map.copyOf(wire),
            Duration.ofSeconds(1)
        ).toCompletableFuture().join();
        Map<?, ?> response = (Map<?, ?>) rawResponse;

        assertThat(response.get("commandId")).isEqualTo(commandId.toString());
        assertThat(response.get("accepted")).isEqualTo(false);
        assertThat(response.get("rejectionReason")).isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED.name());
        assertThat(response.get("message").toString())
            .contains("route manifest fingerprint mismatch")
            .contains(DataAuthorityCommandContracts.routeManifestFingerprint().substring(0, 12));
        assertThat(received.get()).isNull();
        assertThat(refusal.get()).isNotNull();
        assertThat(refusal.get().wire()).containsEntry(
            "routeManifestFingerprint",
            "0000000000000000000000000000000000000000000000000000000000000000"
        );
    }

    @Test
    void commandClientRejectsMismatchedResponseCommandId() {
        UUID commandId = UUID.randomUUID();
        UUID responseCommandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(
            new DataAuthority.CommandResult(
                responseCommandId,
                true,
                9L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            )
        );

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.submit(new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000,
                "3",
                8L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        )).toCompletableFuture().join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority command response commandId mismatch: expected "
                    + commandId + " but received " + responseCommandId
            );
    }

    @Test
    void commandClientRejectsAcceptedResponseWithoutSettledReceipt() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(
            new DataAuthority.CommandResult(
                command.commandId(),
                true,
                9L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            )
        );

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.submit(rankCommand(commandId, playerId, 8L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Authority command accepted result requires a settled receipt");
    }

    @Test
    void commandClientRejectsRejectedResponseWithoutStableReason() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(
            new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.NONE,
                "rejected without stable reason"
            )
        );

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.submit(rankCommand(commandId, playerId, 8L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Authority command rejected result requires a stable rejectionReason");
    }

    @Test
    void commandClientRejectsRejectedResponseWithoutRefusalReceipt() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        bus.subscribeRequest(MessageBusAuthorityChannels.COMMAND, ignored -> CompletableFuture.completedFuture(Map.of(
            "commandId", commandId.toString(),
            "accepted", false,
            "revision", 8L,
            "rejectionReason", DataAuthority.RejectionReason.VALIDATION_FAILED.name(),
            "message", "rejected without refusal receipt",
            "settlement", DataAuthority.CommandSettlement.unsettled(8L).payload()
        )));
        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.submit(rankCommand(commandId, playerId, 8L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Authority command rejected result requires a refusal receipt");
    }

    @Test
    void commandClientRejectsRejectedResponseWithMismatchedRefusalReceipt() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID otherPlayerId = UUID.randomUUID();
        DataAuthority.CommandRefusalReceipt wrongReceipt = DataAuthority.CommandRefusalReceipt.create(
            "message-bus-provider",
            commandId,
            DataAuthority.CommandType.GRANT_RANK.name(),
            "rank:player:" + otherPlayerId,
            "authority-1",
            "authority-1",
            "messagebus:authority-1->authority-1",
            DataAuthority.RejectionReason.VALIDATION_FAILED,
            8L,
            DataAuthorityCommandContracts.fingerprint(),
            DataAuthorityCommandContracts.routeManifestFingerprint(),
            DataAuthority.CommandRefusalReceipt.payloadHash(Map.of(
                "playerId", playerId.toString(),
                "primaryRank", "ADMIN",
                "ranks", List.of("DEFAULT", "ADMIN")
            )),
            System.currentTimeMillis()
        );

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        bus.subscribeRequest(MessageBusAuthorityChannels.COMMAND, ignored -> CompletableFuture.completedFuture(Map.of(
            "commandId", commandId.toString(),
            "accepted", false,
            "revision", 8L,
            "rejectionReason", DataAuthority.RejectionReason.VALIDATION_FAILED.name(),
            "message", "rejected with wrong receipt scope",
            "settlement", DataAuthority.CommandSettlement.unsettled(8L).payload(),
            "refusalReceipt", wrongReceipt.payload()
        )));
        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.submit(rankCommand(commandId, playerId, 8L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority command settlement refusalReceipt.aggregateScope mismatch: expected rank:player:"
                    + playerId + " but received rank:player:" + otherPlayerId
            );
    }

    @Test
    void commandClientRejectsSettledResponseForDifferentPartitionKey() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID responsePlayerId = UUID.randomUUID();
        DataAuthority.CommandSettlement settlement = rankSettlement(
            commandId,
            responsePlayerId,
            "rank:" + commandId,
            9L,
            8L
        );
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(
            new DataAuthority.CommandResult(
                command.commandId(),
                true,
                9L,
                DataAuthority.RejectionReason.NONE,
                "accepted",
                settlement
            )
        );

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.submit(rankCommand(commandId, playerId, 8L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority command settlement partitionKey mismatch: expected rank:player:"
                    + playerId + " but received rank:player:" + responsePlayerId
            );
    }

    @Test
    void commandClientRejectsSettledResponseForDifferentSourceCommandId() {
        UUID commandId = UUID.randomUUID();
        UUID responseSourceCommandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.CommandSettlement settlement = rankSettlement(
            responseSourceCommandId,
            playerId,
            "rank:" + commandId,
            9L,
            8L
        );
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(
            new DataAuthority.CommandResult(
                command.commandId(),
                true,
                9L,
                DataAuthority.RejectionReason.NONE,
                "accepted",
                settlement
            )
        );

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> client.submit(rankCommand(commandId, playerId, 8L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority command settlement watermark.sourceCommandId mismatch: expected "
                    + commandId + " but received " + responseSourceCommandId
            );
    }

    @Test
    void commandProviderRejectsScopePayloadDriftWithoutDelegating() {
        UUID commandId = UUID.randomUUID();
        UUID scopedPlayerId = UUID.randomUUID();
        UUID payloadPlayerId = UUID.randomUUID();
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        AtomicReference<MessageBusDataAuthorityProvider.CommandRefusal> refusal =
            new AtomicReference<>();
        DataAuthority.CommandPort commandPort = command -> {
            received.set(command);
            return CompletableFuture.completedFuture(acceptedResult(command, 1L));
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            refusal::set
        );
        provider.start();

        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("commandId", commandId.toString());
        wire.put("commandType", DataAuthority.CommandType.GRANT_RANK.name());
        wire.put("actorId", "rank-service");
        wire.put("scope", "rank:player:" + scopedPlayerId);
        wire.put("idempotencyKey", "rank:" + commandId);
        wire.put("deadlineEpochMillis", System.currentTimeMillis() + 1000);
        wire.put("fencingToken", "3");
        wire.put("expectedRevision", DataAuthority.ANY_REVISION);
        wire.put("schemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION);
        wire.put("contractFingerprint", DataAuthorityCommandContracts.fingerprint());
        wire.put("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        wire.put("route", rankRoute(scopedPlayerId));
        wire.put("payload", Map.of(
            "playerId", payloadPlayerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN")
        ));

        Object rawResponse = bus.request(
            "authority-1",
            MessageBusAuthorityChannels.COMMAND,
            Map.copyOf(wire),
            Duration.ofSeconds(1)
        ).toCompletableFuture().join();
        Map<?, ?> response = (Map<?, ?>) rawResponse;

        assertThat(response.get("commandId")).isEqualTo(commandId.toString());
        assertThat(response.get("accepted")).isEqualTo(false);
        assertThat(response.get("rejectionReason")).isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE.name());
        assertThat(response.get("message").toString())
            .contains("scope does not match payload aggregate id")
            .contains("expected rank:player:" + payloadPlayerId)
            .contains("but was rank:player:" + scopedPlayerId);
        assertThat(received.get()).isNull();
        assertThat(refusal.get()).isNotNull();
        assertThat(refusal.get().originNode()).isEqualTo("authority-1");
        assertThat(refusal.get().targetNode()).isEqualTo("authority-1");
        assertThat(refusal.get().wire()).containsEntry("scope", "rank:player:" + scopedPlayerId);
        assertThat(refusal.get().result().commandId()).isEqualTo(commandId);
        assertThat(refusal.get().result().rejectionReason())
            .isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE);

        Map<?, ?> receipt = (Map<?, ?>) response.get("refusalReceipt");
        assertThat(receipt).isNotNull();
        assertThat(receipt.get("sourceProvider")).isEqualTo("message-bus-provider");
        assertThat(receipt.get("commandId")).isEqualTo(commandId.toString());
        assertThat(receipt.get("commandType")).isEqualTo(DataAuthority.CommandType.GRANT_RANK.name());
        assertThat(receipt.get("aggregateScope")).isEqualTo("rank:player:" + scopedPlayerId);
        assertThat(receipt.get("originNode")).isEqualTo("authority-1");
        assertThat(receipt.get("targetNode")).isEqualTo("authority-1");
        assertThat(receipt.get("authorityRoute")).isEqualTo("messagebus:authority-1->authority-1");
        assertThat(receipt.get("rejectionReason")).isEqualTo(DataAuthority.RejectionReason.INVALID_SCOPE.name());
        assertThat(receipt.get("contractFingerprint")).isEqualTo(DataAuthorityCommandContracts.fingerprint());
        assertThat(receipt.get("routeManifestFingerprint"))
            .isEqualTo(DataAuthorityCommandContracts.routeManifestFingerprint());
        assertThat(receipt.get("payloadHash")).isEqualTo(DataAuthority.CommandRefusalReceipt.payloadHash(
            (Map<?, ?>) wire.get("payload")
        ));
        assertThat(receipt.get("receiptFingerprint").toString()).matches("[0-9a-f]{64}");
    }

    @Test
    void profileReadProviderRejectsContractFingerprintMismatchWithoutDelegating() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<UUID> received = new AtomicReference<>();

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            id -> {
                received.set(id);
                return CompletableFuture.completedFuture(Optional.empty());
            },
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("readType", DataAuthorityReadContracts.ReadType.PLAYER_PROFILE.name());
        wire.put("schemaVersion", DataAuthorityReadContracts.schemaVersion());
        wire.put("contractFingerprint", "0000000000000000000000000000000000000000000000000000000000000000");
        wire.put("playerId", playerId.toString());

        Object rawResponse = bus.request(
            "authority-1",
            MessageBusAuthorityChannels.PROFILE_READ,
            Map.copyOf(wire),
            Duration.ofSeconds(1)
        ).toCompletableFuture().join();
        Map<?, ?> response = (Map<?, ?>) rawResponse;

        assertThat(response.get("found")).isEqualTo(false);
        assertThat(response.get("rejectionReason")).isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED.name());
        assertThat(response.get("message").toString())
            .contains("read contract fingerprint mismatch")
            .contains(DataAuthorityReadContracts.fingerprint().substring(0, 12));
        assertThat(received.get()).isNull();
    }

    @Test
    void rankReadProviderRejectsContractFingerprintMismatchWithoutDelegating() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<UUID> received = new AtomicReference<>();

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            id -> {
                received.set(id);
                return CompletableFuture.completedFuture(Optional.empty());
            }
        );
        provider.start();

        Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("readType", DataAuthorityReadContracts.ReadType.PLAYER_RANK.name());
        wire.put("schemaVersion", DataAuthorityReadContracts.schemaVersion());
        wire.put("contractFingerprint", "0000000000000000000000000000000000000000000000000000000000000000");
        wire.put("playerId", playerId.toString());
        wire.put("minimumRevision", 4L);
        wire.put("maxAgeMillis", 750L);

        Object rawResponse = bus.request(
            "authority-1",
            MessageBusAuthorityChannels.RANK_READ,
            Map.copyOf(wire),
            Duration.ofSeconds(1)
        ).toCompletableFuture().join();
        Map<?, ?> response = (Map<?, ?>) rawResponse;
        Map<?, ?> quote = (Map<?, ?>) response.get("quote");

        assertThat(response.get("found")).isEqualTo(false);
        assertThat(response.get("rejectionReason")).isEqualTo(DataAuthority.RejectionReason.VALIDATION_FAILED.name());
        assertThat(response.get("message").toString())
            .contains("read contract fingerprint mismatch")
            .contains(DataAuthorityReadContracts.fingerprint().substring(0, 12));
        assertThat(quote.get("status")).isEqualTo(DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE.name());
        assertThat(quote.get("requiredRevision")).isEqualTo(4L);
        assertThat(received.get()).isNull();
    }

    @Test
    void acceptedRankCommandPublishesWatermarkedSnapshotInvalidation() {
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            commandId,
            eventId,
            9L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        AtomicReference<AuthoritySnapshotInvalidation> invalidation = new AtomicReference<>();
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(acceptedResult(
            command,
            9L
        ));

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        bus.subscribe(MessageBusAuthorityChannels.SNAPSHOT_INVALIDATION, envelope -> invalidation.set(
            AuthoritySnapshotInvalidation.fromJson(envelope.getPayload().toString()).orElse(null)
        ));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.of(new DataAuthority.PlayerRankSnapshot(
                id,
                "ADMIN",
                List.of("DEFAULT", "ADMIN"),
                9L,
                watermark
            )))
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );
        DataAuthority.CommandResult result = client.submit(new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000,
                "8",
                8L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(invalidation.get()).isNotNull();
        assertThat(invalidation.get().projectionFamily()).isEqualTo(AuthoritySnapshotInvalidation.PLAYER_RANK);
        assertThat(invalidation.get().aggregateScope()).isEqualTo("rank:player:" + playerId);
        assertThat(invalidation.get().revision()).isEqualTo(9L);
        assertThat(invalidation.get().watermark()).isEqualTo(watermark);
    }

    @Test
    void acceptedRankCommandPublishesRevisionFloorWhenSnapshotReadbackLags() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<AuthoritySnapshotInvalidation> invalidation = new AtomicReference<>();
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(acceptedResult(
            command,
            9L
        ));

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        bus.subscribe(MessageBusAuthorityChannels.SNAPSHOT_INVALIDATION, envelope -> invalidation.set(
            AuthoritySnapshotInvalidation.fromJson(envelope.getPayload().toString()).orElse(null)
        ));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );
        DataAuthority.CommandResult result = client.submit(new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000,
                "8",
                8L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(invalidation.get()).isNotNull();
        assertThat(invalidation.get().projectionFamily()).isEqualTo(AuthoritySnapshotInvalidation.PLAYER_RANK);
        assertThat(invalidation.get().aggregateScope()).isEqualTo("rank:player:" + playerId);
        assertThat(invalidation.get().revision()).isEqualTo(9L);
        assertThat(invalidation.get().watermark()).isNull();
    }

    @Test
    void acceptedProfileCommandPublishesRevisionFloorWhenSnapshotReadbackLags() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<AuthoritySnapshotInvalidation> invalidation = new AtomicReference<>();
        DataAuthority.CommandPort commandPort = command -> CompletableFuture.completedFuture(acceptedResult(
            command,
            5L
        ));

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        bus.subscribe(MessageBusAuthorityChannels.SNAPSHOT_INVALIDATION, envelope -> invalidation.set(
            AuthoritySnapshotInvalidation.fromJson(envelope.getPayload().toString()).orElse(null)
        ));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            id -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );
        DataAuthority.CommandResult result = client.submit(new DataAuthority.PlayerProfileCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
                "profile-service",
                "player:" + playerId,
                "profile:" + commandId,
                System.currentTimeMillis() + 1000,
                "8",
                4L
            ),
            playerId,
            "Notch",
            System.currentTimeMillis(),
            "paper-1",
            "proxy-1",
            "127.0.0.1",
            "world",
            "0,64,0",
            "SURVIVAL",
            1,
            0.5F,
            20.0D,
            20,
            "lastProxySession"
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(invalidation.get()).isNotNull();
        assertThat(invalidation.get().projectionFamily()).isEqualTo(AuthoritySnapshotInvalidation.PLAYER_PROFILE);
        assertThat(invalidation.get().aggregateScope()).isEqualTo("player:" + playerId);
        assertThat(invalidation.get().revision()).isEqualTo(5L);
        assertThat(invalidation.get().watermark()).isNull();
    }

    @Test
    void commandProviderRejectsReservedActorSpoofFromDifferentSender() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        AtomicReference<MessageBusDataAuthorityProvider.CommandRefusal> refusal = new AtomicReference<>();
        DataAuthority.CommandPort commandPort = new AuthorityPrincipalCommandPort(command -> {
            received.set(command);
            return CompletableFuture.completedFuture(acceptedResult(command, 9L));
        });

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            refusal::set
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        DataAuthority.CommandResult result = client.submit(new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "node:paper-2",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000,
                "3",
                8L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(received.get()).isNull();
        assertThat(refusal.get()).isNotNull();
        assertThat(refusal.get().originNode()).isEqualTo("authority-1");
        assertThat(refusal.get().targetNode()).isEqualTo("authority-1");
        assertThat(refusal.get().wire()).containsEntry("actorId", "node:paper-2");
        assertThat(refusal.get().result().commandId()).isEqualTo(commandId);
        assertThat(refusal.get().result().rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(result.refusalReceipt()).isNotNull();
        assertThat(result.refusalReceipt().refused()).isTrue();
        assertThat(result.refusalReceipt().commandId()).isEqualTo(commandId);
        assertThat(result.refusalReceipt().commandType()).isEqualTo(DataAuthority.CommandType.GRANT_RANK.name());
        assertThat(result.refusalReceipt().aggregateScope()).isEqualTo("rank:player:" + playerId);
        assertThat(result.refusalReceipt().rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(result.refusalReceipt().contractFingerprint()).isEqualTo(DataAuthorityCommandContracts.fingerprint());
        assertThat(result.refusalReceipt().routeManifestFingerprint())
            .isEqualTo(DataAuthorityCommandContracts.routeManifestFingerprint());
        assertThat(result.refusalReceipt().receiptFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void commandProviderStampsAuthorityOwnedFenceBeforeExecution() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        DataAuthority.CommandPort commandPort = new AuthorityPrincipalCommandPort(
            new AuthorityFencingCommandPort(
                command -> {
                    received.set(command);
                    return CompletableFuture.completedFuture(acceptedResult(command, 9L));
                },
                (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                    commandDomain,
                    commandTopic,
                    partitionKey,
                    ownerNode,
                    12L,
                    null,
                    0L,
                    Instant.EPOCH
                ),
                "authority-1"
            )
        );

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-1",
            Duration.ofSeconds(1)
        );

        DataAuthority.CommandResult result = client.submit(new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000,
                "999",
                8L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(received.get()).isNotNull();
        assertThat(received.get().fencingToken()).startsWith("claim:v1:12:");
        assertThat(received.get().expectedRevision()).isEqualTo(8L);
        assertThat(received.get().provenance().verifiedPrincipal()).isEqualTo("node:authority-1");
    }

    @Test
    void rankReaderReturnsSnapshotThroughProvider() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            commandId,
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerRankSnapshot rankSnapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            watermark
        );
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(rankSnapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "rank:player:" + id,
                    "player_rank",
                    requirement.minimumRevision(),
                    4L,
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    watermark,
                    null,
                    DataAuthority.ReadProvenance.authority(),
                    DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_rank", watermark)
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(rankSnapshot, quote));
            }
        };
        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            rankReader
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");
        Optional<DataAuthority.PlayerRankSnapshot> snapshot = client.findRanks(playerId)
            .toCompletableFuture()
            .join();

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().playerId()).isEqualTo(playerId);
        assertThat(snapshot.get().primaryRank()).isEqualTo("ADMIN");
        assertThat(snapshot.get().revision()).isEqualTo(4L);
        assertThat(snapshot.get().watermark()).isEqualTo(watermark);
        assertThat(snapshot.get().watermark().watermarked()).isTrue();
    }

    @Test
    void rankReaderFindReturnsEmptyForUnsatisfiedQuote() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "rank:player:" + id,
                    "player_rank",
                    requirement.minimumRevision(),
                    0L,
                    DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE,
                    null,
                    "rank projection did not satisfy the requested read"
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.unsatisfied(quote));
            }
        };
        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            rankReader
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");

        assertThat(client.findRanks(playerId).toCompletableFuture().join()).isEmpty();
    }

    @Test
    void rankClientRejectsFoundSnapshotForDifferentPlayer() {
        UUID requestedPlayerId = UUID.randomUUID();
        UUID returnedPlayerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + returnedPlayerId,
            "player_rank",
            returnedPlayerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + returnedPlayerId,
            commandId,
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerRankSnapshot returnedSnapshot = new DataAuthority.PlayerRankSnapshot(
            returnedPlayerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            watermark
        );
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(returnedSnapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "rank:player:" + id,
                    "player_rank",
                    requirement.minimumRevision(),
                    4L,
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    watermark,
                    null
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(returnedSnapshot, quote));
            }
        };
        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            rankReader
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");

        assertThatThrownBy(() -> client.findRanks(requestedPlayerId).toCompletableFuture().join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority rank response playerId mismatch: expected "
                    + requestedPlayerId + " but received " + returnedPlayerId
            );
    }

    @Test
    void quotedRankReaderPreservesRequirementAndProvenanceThroughProvider() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            commandId,
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            watermark
        );
        AtomicReference<DataAuthority.ReadRequirement> observedRequirement = new AtomicReference<>();
        DataAuthority.AuthorityBootIdentity bootIdentity = authorityBootIdentity();
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                observedRequirement.set(requirement);
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "rank:player:" + id,
                    "player_rank",
                    requirement.minimumRevision(),
                    4L,
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    watermark,
                    null,
                    DataAuthority.ReadProvenance.cache(1_000L, 1_250L, 5_000L),
                    DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_rank", watermark)
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(snapshot, quote));
            }
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            rankReader,
            bootIdentity,
            ignored -> {
            }
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = client
            .quoteRanks(playerId, DataAuthority.ReadRequirement.freshAtLeast(4L, 750L))
            .toCompletableFuture()
            .join();

        assertThat(observedRequirement.get().minimumRevision()).isEqualTo(4L);
        assertThat(observedRequirement.get().maxAgeMillis()).isEqualTo(750L);
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).contains(snapshot);
        assertThat(read.quote().requiredRevision()).isEqualTo(4L);
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(read.quote().provenance().cacheAgeMillis()).isEqualTo(250L);
        assertThat(read.quote().provenance().maxAgeMillis()).isEqualTo(5_000L);
        assertThat(read.quote().provenance().authorityBoot()).isEqualTo(bootIdentity);
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().sourceEventId()).isEqualTo(eventId);
        assertThat(read.quote().deliveryReceipt().satisfies("player_rank", "rank:player:" + playerId, 4L))
            .isTrue();
    }

    @Test
    void quotedProfileReaderPreservesRequirementAndProvenanceThroughProvider() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "player:" + playerId,
            "player_profile",
            playerId.toString(),
            "player_profile",
            "state.player_profile",
            "player:" + playerId,
            commandId,
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            "notch",
            true,
            "paper-1",
            "proxy-1",
            100L,
            Map.of(),
            4L,
            watermark
        );
        AtomicReference<DataAuthority.ReadRequirement> observedRequirement = new AtomicReference<>();
        DataAuthority.AuthorityBootIdentity bootIdentity = authorityBootIdentity();
        DataAuthority.PlayerProfileReader profileReader = new DataAuthority.PlayerProfileReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                observedRequirement.set(requirement);
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "player:" + id,
                    "player_profile",
                    requirement.minimumRevision(),
                    4L,
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    watermark,
                    null,
                    DataAuthority.ReadProvenance.cache(1_000L, 1_250L, 5_000L),
                    DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_profile", watermark)
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(snapshot, quote));
            }
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            profileReader,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            bootIdentity,
            ignored -> {
            }
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> read = client
            .quoteProfile(playerId, DataAuthority.ReadRequirement.freshAtLeast(4L, 750L))
            .toCompletableFuture()
            .join();

        assertThat(observedRequirement.get().minimumRevision()).isEqualTo(4L);
        assertThat(observedRequirement.get().maxAgeMillis()).isEqualTo(750L);
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).contains(snapshot);
        assertThat(read.quote().requiredRevision()).isEqualTo(4L);
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(read.quote().provenance().cacheAgeMillis()).isEqualTo(250L);
        assertThat(read.quote().provenance().authorityBoot()).isEqualTo(bootIdentity);
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().sourceEventId()).isEqualTo(eventId);
        assertThat(read.quote().deliveryReceipt().satisfies("player_profile", "player:" + playerId, 4L))
            .isTrue();
    }

    @Test
    void profileClientRejectsQuoteForDifferentAggregateScope() {
        UUID requestedPlayerId = UUID.randomUUID();
        UUID returnedPlayerId = UUID.randomUUID();
        DataAuthority.PlayerProfileReader profileReader = new DataAuthority.PlayerProfileReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID id) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "player:" + returnedPlayerId,
                    "player_profile",
                    requirement.minimumRevision(),
                    0L,
                    DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE,
                    null,
                    "projection did not satisfy requested player"
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.unsatisfied(quote));
            }
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            profileReader,
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");

        assertThatThrownBy(() -> client
            .quoteProfile(requestedPlayerId, DataAuthority.ReadRequirement.freshAtLeast(4L, 750L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority read quote aggregateScope mismatch: expected player:"
                    + requestedPlayerId + " but received player:" + returnedPlayerId
            );
    }

    @Test
    void rankClientRejectsSatisfiedQuoteWithoutDeliveryReceipt() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            commandId,
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            watermark
        );
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "rank:player:" + id,
                    "player_rank",
                    requirement.minimumRevision(),
                    4L,
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    watermark,
                    null,
                    DataAuthority.ReadProvenance.authority()
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(snapshot, quote));
            }
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            rankReader
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");

        assertThatThrownBy(() -> client
            .quoteRanks(playerId, DataAuthority.ReadRequirement.freshAtLeast(4L, 750L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Authority read quote deliveryReceipt is required for satisfied reads");
    }

    @Test
    void profileClientRejectsDeliveryReceiptBelowRequestedRevision() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "player:" + playerId,
            "player_profile",
            playerId.toString(),
            "player_profile",
            "state.player_profile",
            "player:" + playerId,
            commandId,
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            "notch",
            true,
            "paper-1",
            "proxy-1",
            100L,
            Map.of(),
            4L,
            watermark
        );
        DataAuthority.ProjectionDeliveryReceipt staleReceipt = new DataAuthority.ProjectionDeliveryReceipt(
            "postgres-authority-state",
            "player_profile",
            "player:" + playerId,
            "state.player_profile",
            eventId,
            3L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerProfileReader profileReader = new DataAuthority.PlayerProfileReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "player:" + id,
                    "player_profile",
                    requirement.minimumRevision(),
                    4L,
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    watermark,
                    null,
                    DataAuthority.ReadProvenance.authority(),
                    staleReceipt
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(snapshot, quote));
            }
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            profileReader,
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");

        assertThatThrownBy(() -> client
            .quoteProfile(playerId, DataAuthority.ReadRequirement.freshAtLeast(4L, 750L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority read deliveryReceipt deliveredRevision is below request: expected at least 4 but received 3"
            );
    }

    @Test
    void rankClientRejectsDeliveryReceiptStateTopicMismatch() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            commandId,
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            watermark
        );
        DataAuthority.ProjectionDeliveryReceipt wrongTopicReceipt = new DataAuthority.ProjectionDeliveryReceipt(
            "postgres-authority-state",
            "player_rank",
            "rank:player:" + playerId,
            "state.player_profile",
            eventId,
            4L,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "rank:player:" + id,
                    "player_rank",
                    requirement.minimumRevision(),
                    4L,
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    watermark,
                    null,
                    DataAuthority.ReadProvenance.authority(),
                    wrongTopicReceipt
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(snapshot, quote));
            }
        };

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-1"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.VALIDATION_FAILED,
                "not used"
            )),
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            rankReader
        );
        provider.start();

        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(bus, "authority-1");

        assertThatThrownBy(() -> client
            .quoteRanks(playerId, DataAuthority.ReadRequirement.freshAtLeast(4L, 750L))
            .toCompletableFuture()
            .join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Authority read quote deliveryReceipt.stateTopic mismatch: "
                    + "expected state.player_rank but received state.player_profile"
            );
    }

    private static DataAuthority.AuthorityBootIdentity authorityBootIdentity() {
        return new DataAuthority.AuthorityBootIdentity(
            "registry-1",
            "authority-1",
            "startup-fingerprint",
            900L,
            "message-bus-provider",
            "read-contract"
        );
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        UUID commandId,
        UUID playerId,
        long expectedRevision
    ) {
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000,
                "3",
                expectedRevision
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }

    private static DataAuthority.CommandResult acceptedResult(
        DataAuthority.AuthorityCommand command,
        long revision
    ) {
        return new DataAuthority.CommandResult(
            command.commandId(),
            true,
            revision,
            DataAuthority.RejectionReason.NONE,
            "accepted",
            settlement(command, revision)
        );
    }

    private static DataAuthority.CommandSettlement settlement(
        DataAuthority.AuthorityCommand command,
        long revision
    ) {
        Map<String, Object> route = DataAuthorityCommandContracts.routePayload(command);
        Object aggregateId = command.payload().get(
            DataAuthorityCommandContracts.contract(command.type()).aggregateIdField()
        );
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "message-bus-test-authority",
            command.scope(),
            string(route.get("domain")),
            aggregateId == null ? command.scope() : aggregateId.toString(),
            string(route.get("domain")),
            string(route.get("stateTopic")),
            string(route.get("partitionKey")),
            command.commandId(),
            UUID.randomUUID(),
            revision,
            1234L,
            "state-fingerprint:" + command.commandId(),
            "event-chain:" + command.commandId()
        );
        return new DataAuthority.CommandSettlement(
            "message-bus-test-authority",
            string(route.get("domain")),
            string(route.get("commandTopic")),
            string(route.get("eventTopic")),
            string(route.get("stateTopic")),
            string(route.get("partitionKey")),
            command.fencingToken(),
            command.idempotencyKey(),
            command.expectedRevision(),
            watermark
        );
    }

    private static DataAuthority.CommandSettlement rankSettlement(
        UUID sourceCommandId,
        UUID playerId,
        String idempotencyKey,
        long revision,
        long expectedRevision
    ) {
        UUID eventId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            sourceCommandId,
            eventId,
            revision,
            1234L,
            "state-fingerprint",
            "event-chain-hash"
        );
        return new DataAuthority.CommandSettlement(
            "postgres-authority-state",
            "player_rank",
            "cmd.player_rank",
            "evt.player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            "12",
            idempotencyKey,
            expectedRevision,
            watermark
        );
    }

    private static Map<String, Object> rankRoute(UUID playerId) {
        return Map.of(
            "domain", "player_rank",
            "commandTopic", "cmd.player_rank",
            "eventTopic", "evt.player_rank",
            "stateTopic", "state.player_rank",
            "partitionKey", "rank:player:" + playerId
        );
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private record TestAdapter(String serverId) implements MessageBusAdapter {
        @Override
        public String getServerId() {
            return serverId;
        }

        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }

        @Override
        public Logger getLogger() {
            return Logger.getLogger(MessageBusDataAuthorityTransportTest.class.getName());
        }

        @Override
        public MessageBusConnectionConfig getConnectionConfig() {
            return MessageBusConnectionConfig.builder()
                .type(MessageBusConnectionConfig.MessageBusType.IN_MEMORY)
                .build();
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
