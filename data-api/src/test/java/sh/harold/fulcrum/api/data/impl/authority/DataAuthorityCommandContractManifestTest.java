package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.messagebus.MessageBusDataAuthorityClient;
import sh.harold.fulcrum.api.data.impl.messagebus.MessageBusDataAuthorityProvider;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.InMemoryMessageBus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAuthorityCommandContractManifestTest {
    private static final Map<DataAuthority.CommandType, DataAuthorityCommandContracts.CommandContract> CONTRACTS =
        DataAuthorityCommandContracts.all();

    @Test
    void contractManifestCoversEveryCommandType() {
        assertThat(CONTRACTS.keySet()).containsExactlyInAnyOrderElementsOf(
            EnumSet.allOf(DataAuthority.CommandType.class)
        );
        for (DataAuthorityCommandContracts.CommandContract contract : CONTRACTS.values()) {
            assertThat(contract.allowedPayloadFields())
                .as(contract.type() + " allowed payload fields")
                .containsAll(contract.requiredPayloadFields());
            assertThat(contract.domain())
                .as(contract.type() + " route domain")
                .isEqualTo(AuthorityCommandRoute.from(contract.type(), "sample").domain());
            assertThat(contract.deliveryMode())
                .as(contract.type() + " delivery mode")
                .isNotNull();
            assertThat(contract.commandLogStore())
                .as(contract.type() + " command log store")
                .isEqualTo("kafka");
            assertThat(contract.hotProjectionStore())
                .as(contract.type() + " hot projection store")
                .isEqualTo("cassandra");
            assertThat(contract.historyStore())
                .as(contract.type() + " history store")
                .isEqualTo("postgresql");
            assertThat(contract.cacheStore())
                .as(contract.type() + " cache store")
                .isEqualTo("valkey");
        }
    }

    @Test
    void contractManifestClassifiesSyncAndAsyncCommands() {
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.RECORD_PLAYER_LOGIN))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.RECORD_PLAYER_LOGOUT))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.RECORD_MATCH_START))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.RECORD_MATCH_END))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.GRANT_RANK))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.REVOKE_RANK))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.START_SESSION))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.RENEW_SESSION))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(DataAuthorityCommandContracts.deliveryMode(DataAuthority.CommandType.END_SESSION))
            .isEqualTo(DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE);
    }

    @Test
    void contractManifestClassifiesCompareRequiredCommands() {
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.RECORD_PLAYER_LOGIN))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.RECORD_PLAYER_LOGOUT))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.START_SESSION))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.RENEW_SESSION))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.END_SESSION))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.RECORD_MATCH_START))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.RECORD_MATCH_END))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.GRANT_RANK))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.COMPARE_REQUIRED);
        assertThat(DataAuthorityCommandContracts.revisionPolicy(DataAuthority.CommandType.REVOKE_RANK))
            .isEqualTo(DataAuthorityCommandContracts.CommandRevisionPolicy.COMPARE_REQUIRED);
    }

    @Test
    void contractManifestFingerprintIsStable() throws Exception {
        String actual = DataAuthorityCommandContracts.fingerprint();

        assertThat(actual).matches("[0-9a-f]{64}");
        assertThat(actual).isEqualTo(goldenFingerprint("/contracts/data-authority-command-contract.sha256"));
    }

    @Test
    void routeManifestFingerprintIsStable() throws Exception {
        String actual = DataAuthorityCommandContracts.routeManifestFingerprint();

        assertThat(actual).matches("[0-9a-f]{64}");
        assertThat(actual).isEqualTo(goldenFingerprint("/contracts/data-authority-command-route-manifest.sha256"));
    }

    @Test
    void routePartitionKeyVectorsCoverEveryCommandType() {
        Map<String, String> vectors = DataAuthorityCommandContracts.routePartitionKeyVectors();

        assertThat(vectors.keySet()).containsExactlyInAnyOrderElementsOf(
            CONTRACTS.keySet().stream().map(DataAuthority.CommandType::name).toList()
        );
        assertThat(vectors)
            .containsEntry("GRANT_RANK", "rank:player:{aggregateId}=>rank:player:{aggregateId}")
            .containsEntry("RECORD_PLAYER_LOGIN", "player:{aggregateId}=>player:{aggregateId}")
            .containsEntry("RECORD_MATCH_START", "match:{aggregateId}=>match:{aggregateId}");
    }

    @Test
    void contractCommandsRoundTripThroughMessageBusTransport() {
        EnumMap<DataAuthority.CommandType, DataAuthority.AuthorityCommand> received =
            new EnumMap<>(DataAuthority.CommandType.class);
        DataAuthority.CommandPort commandPort = new AuthorityPrincipalCommandPort(command -> {
            received.put(command.type(), command);
            return CompletableFuture.completedFuture(acceptedResult(
                command,
                Math.max(1L, command.expectedRevision() + 1L)
            ));
        });

        InMemoryMessageBus bus = new InMemoryMessageBus(new TestAdapter("authority-contract-test"));
        MessageBusDataAuthorityProvider provider = new MessageBusDataAuthorityProvider(
            bus,
            commandPort,
            ignored -> CompletableFuture.completedFuture(Optional.empty()),
            ignored -> CompletableFuture.completedFuture(Optional.empty())
        );
        provider.start();
        MessageBusDataAuthorityClient client = new MessageBusDataAuthorityClient(
            bus,
            "authority-contract-test",
            Duration.ofSeconds(1)
        );

        for (DataAuthorityCommandContracts.CommandContract contract : CONTRACTS.values()) {
            DataAuthority.AuthorityCommand command = sampleCommand(contract.type());
            DataAuthority.CommandResult result = client.submit(command).toCompletableFuture().join();
            DataAuthority.AuthorityCommand decoded = received.get(contract.type());

            assertThat(result.accepted()).as(contract.type() + " result").isTrue();
            assertThat(decoded).as(contract.type() + " decoded command").isInstanceOf(contract.commandClass());
            assertThat(decoded.actorId()).as(contract.type() + " actor").isEqualTo("node:authority-contract-test");
            assertThat(AuthorityCommandRoute.fromCommand(decoded).domain()).isEqualTo(contract.domain());
            assertThat(decoded.provenance().verifiedPrincipal()).isEqualTo("node:authority-contract-test");
            AuthorityCommandFrame frame = AuthorityCommandFrame.fromCommand(decoded);
            assertThat(frame.manifestPayload())
                .containsEntry("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
            assertThat(frame.toCommand().payload()).isEqualTo(decoded.payload());
        }
    }

    @Test
    void contractRejectsUnknownTopLevelPayloadFields() {
        DataAuthority.AuthorityCommand command = sampleCommand(DataAuthority.CommandType.GRANT_RANK);
        Map<String, Object> payload = new java.util.LinkedHashMap<>(command.payload());
        payload.put("legacyCollection", "player_ranks");

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            command.type(),
            command.actorId(),
            command.scope(),
            command.idempotencyKey(),
            command.deadlineEpochMillis(),
            command.fencingToken(),
            command.expectedRevision(),
            DataAuthority.COMMAND_SCHEMA_VERSION,
            AuthorityCommandRoute.fromCommand(command),
            command.provenance(),
            payload
        )).hasMessageContaining("legacyCollection");
    }

    @Test
    void contractRejectsUnsupportedSchemaVersion() {
        DataAuthority.AuthorityCommand command = sampleCommand(DataAuthority.CommandType.GRANT_RANK);

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            command.type(),
            command.actorId(),
            command.scope(),
            command.idempotencyKey(),
            command.deadlineEpochMillis(),
            command.fencingToken(),
            command.expectedRevision(),
            DataAuthority.COMMAND_SCHEMA_VERSION + 1,
            AuthorityCommandRoute.fromCommand(command),
            command.provenance(),
            command.payload()
        )).hasMessageContaining("schema version");
    }

    @Test
    void contractRejectsRouteDomainDrift() {
        DataAuthority.AuthorityCommand command = sampleCommand(DataAuthority.CommandType.GRANT_RANK);

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            command.type(),
            command.actorId(),
            command.scope(),
            command.idempotencyKey(),
            command.deadlineEpochMillis(),
            command.fencingToken(),
            command.expectedRevision(),
            DataAuthority.COMMAND_SCHEMA_VERSION,
            new AuthorityCommandRoute("player_profile", "cmd.player_profile", "evt.player_profile",
                "state.player_profile", command.scope()),
            command.provenance(),
            command.payload()
        )).hasMessageContaining("route domain");
    }

    @Test
    void contractRejectsPartitionKeyDrift() {
        DataAuthority.AuthorityCommand command = sampleCommand(DataAuthority.CommandType.GRANT_RANK);

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            command.type(),
            command.actorId(),
            command.scope(),
            command.idempotencyKey(),
            command.deadlineEpochMillis(),
            command.fencingToken(),
            command.expectedRevision(),
            DataAuthority.COMMAND_SCHEMA_VERSION,
            new AuthorityCommandRoute("player_rank", "cmd.player_rank", "evt.player_rank",
                "state.player_rank", "rank:player:" + UUID.randomUUID()),
            command.provenance(),
            command.payload()
        )).hasMessageContaining("partition key");
    }

    @Test
    void contractRejectsScopePayloadAggregateDrift() {
        DataAuthority.PlayerRankCommand command = (DataAuthority.PlayerRankCommand)
            sampleCommand(DataAuthority.CommandType.GRANT_RANK);
        Map<String, Object> payload = new java.util.LinkedHashMap<>(command.payload());
        UUID otherPlayerId = UUID.randomUUID();
        payload.put("playerId", otherPlayerId.toString());

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            command.type(),
            command.actorId(),
            command.scope(),
            command.idempotencyKey(),
            command.deadlineEpochMillis(),
            command.fencingToken(),
            command.expectedRevision(),
            DataAuthority.COMMAND_SCHEMA_VERSION,
            AuthorityCommandRoute.fromCommand(command),
            command.provenance(),
            payload
        ))
            .isInstanceOf(DataAuthorityCommandContracts.CommandContractViolation.class)
            .hasMessageContaining("scope does not match payload aggregate id")
            .hasMessageContaining("expected rank:player:" + otherPlayerId)
            .hasMessageContaining("but was " + command.scope());
    }

    @Test
    void contractRejectsAnyRevisionForCompareRequiredCommands() {
        DataAuthority.AuthorityCommand command =
            sampleCommand(DataAuthority.CommandType.GRANT_RANK, DataAuthority.ANY_REVISION);

        assertThatThrownBy(() -> AuthorityCommandFrame.fromCommand(command))
            .isInstanceOf(DataAuthorityCommandContracts.CommandContractViolation.class)
            .hasMessageContaining("requires a concrete expectedRevision")
            .satisfies(exception -> assertThat(
                ((DataAuthorityCommandContracts.CommandContractViolation) exception).rejectionReason()
            ).isEqualTo(DataAuthority.RejectionReason.STALE_REVISION));
    }

    private static DataAuthority.AuthorityCommand sampleCommand(DataAuthority.CommandType type) {
        return sampleCommand(type, expectedRevisionFor(type));
    }

    private static DataAuthority.AuthorityCommand sampleCommand(
        DataAuthority.CommandType type,
        long expectedRevision
    ) {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        return switch (type) {
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT -> new DataAuthority.PlayerProfileCommand(
                manifest(commandId, type, "player:" + playerId, now, expectedRevision),
                playerId,
                "ContractUser",
                now,
                "lobby-1",
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
            );
            case START_SESSION, RENEW_SESSION, END_SESSION -> new DataAuthority.PlayerSessionCommand(
                manifest(commandId, type, "player:" + playerId, now, expectedRevision),
                playerId,
                "ContractUser",
                UUID.randomUUID(),
                now,
                "lobby-1",
                "proxy-1",
                "127.0.0.1",
                765,
                type == DataAuthority.CommandType.END_SESSION ? "quit" : null
            );
            case GRANT_RANK, REVOKE_RANK -> new DataAuthority.PlayerRankCommand(
                manifest(commandId, type, "rank:player:" + playerId, now, expectedRevision),
                playerId,
                type == DataAuthority.CommandType.GRANT_RANK ? "ADMIN" : "DEFAULT",
                type == DataAuthority.CommandType.GRANT_RANK
                    ? List.of("DEFAULT", "ADMIN")
                    : List.of("DEFAULT")
            );
            case RECORD_MATCH_START, RECORD_MATCH_END -> new DataAuthority.MatchCommand(
                manifest(commandId, type, "match:" + matchId, now, expectedRevision),
                matchId,
                "duels",
                "arena-1",
                "server-1",
                "slot-1",
                type == DataAuthority.CommandType.RECORD_MATCH_START ? "STARTED" : "ENDED",
                now,
                type == DataAuthority.CommandType.RECORD_MATCH_END ? now + 1000L : null,
                Map.of("variant", "standard"),
                List.of(new DataAuthority.MatchParticipant(
                    playerId,
                    "red",
                    type == DataAuthority.CommandType.RECORD_MATCH_END ? 1 : null,
                    "ACTIVE",
                    Map.of("kills", 1)
                ))
            );
        };
    }

    private static long expectedRevisionFor(DataAuthority.CommandType type) {
        return switch (type) {
            case GRANT_RANK, REVOKE_RANK -> 1L;
            default -> DataAuthority.ANY_REVISION;
        };
    }

    private static DataAuthority.CommandManifest manifest(
        UUID commandId,
        DataAuthority.CommandType type,
        String scope,
        long now,
        long expectedRevision
    ) {
        return DataAuthority.CommandManifest.create(
            commandId,
            type,
            "contract-test",
            scope,
            type.name() + ":" + commandId,
            now + 5000L,
            "1",
            expectedRevision
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
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        Object aggregateId = command.payload()
            .get(DataAuthorityCommandContracts.contract(command.type()).aggregateIdField());
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "message-bus-test-authority",
            command.scope(),
            route.domain(),
            aggregateId == null ? command.scope() : aggregateId.toString(),
            route.domain(),
            route.stateTopic(),
            route.partitionKey(),
            command.commandId(),
            UUID.nameUUIDFromBytes(("event:" + command.commandId()).getBytes(StandardCharsets.UTF_8)),
            revision,
            1_234L,
            "state-fingerprint:" + command.commandId(),
            "event-chain:" + command.commandId()
        );
        return new DataAuthority.CommandSettlement(
            "message-bus-test-authority",
            route.domain(),
            route.commandTopic(),
            route.eventTopic(),
            route.stateTopic(),
            route.partitionKey(),
            command.fencingToken(),
            command.idempotencyKey(),
            command.expectedRevision(),
            watermark
        );
    }

    private static String goldenFingerprint(String path) throws Exception {
        try (InputStream input = DataAuthorityCommandContractManifestTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new AssertionError("Missing golden contract fingerprint resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
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
            return Logger.getLogger(DataAuthorityCommandContractManifestTest.class.getName());
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
