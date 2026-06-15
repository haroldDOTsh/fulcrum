package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAuthorityCommandContractManifestTest {
    private static final Map<String, AuthorityCommandManifest.CommandContract> CONTRACTS =
        AuthorityCommandManifest.allByDeclarationId();

    @Test
    void contractManifestCoversEveryDeclaredCommand() {
        assertThat(CONTRACTS.keySet()).containsExactlyInAnyOrderElementsOf(
            AuthorityDomainDeclarations.declarationIds()
        );
        for (AuthorityCommandManifest.CommandContract contract : CONTRACTS.values()) {
            assertThat(contract.declarationId()).as(contract.declarationId() + " declaration id")
                .isNotBlank();
            assertThat(contract.allowedPayloadFields())
                .as(contract.declarationId() + " allowed payload fields")
                .containsAll(contract.requiredPayloadFields());
            assertThat(contract.domain())
                .as(contract.declarationId() + " route domain")
                .isEqualTo(AuthorityCommandRoute.fromDeclarationId(contract.declarationId(), "sample").domain());
            assertThat(contract.deliveryMode())
                .as(contract.declarationId() + " delivery mode")
                .isNotNull();
            assertThat(contract.commandLogStore())
                .as(contract.declarationId() + " command log store")
                .isEqualTo("kafka");
            assertThat(contract.hotProjectionStore())
                .as(contract.declarationId() + " hot projection store")
                .isEqualTo("cassandra");
            assertThat(contract.historyStore())
                .as(contract.declarationId() + " history store")
                .isEqualTo("postgresql");
            assertThat(contract.cacheStore())
                .as(contract.declarationId() + " cache store")
                .isEqualTo("valkey");
        }
    }

    @Test
    void contractManifestIsDerivedFromDomainDeclarations() {
        for (AuthorityDomainDeclarations.DomainDeclaration domain : AuthorityDomainDeclarations.all().values()) {
            for (AuthorityDomainDeclarations.CommandDeclaration command : domain.commands()) {
                AuthorityCommandManifest.CommandContract contract = CONTRACTS.get(command.declarationId());

                assertThat(contract).as(command.declarationId() + " contract").isNotNull();
                assertThat(contract.declarationId()).isEqualTo(command.declarationId());
                assertThat(contract.domain()).isEqualTo(domain.domain());
                assertThat(AuthorityDomainDeclarations.command(command.declarationId()).declarationId())
                    .isEqualTo(command.declarationId());
                assertThat(contract.deliveryMode()).isEqualTo(command.deliveryMode());
                assertThat(contract.revisionPolicy()).isEqualTo(command.revisionPolicy());
                assertThat(contract.commandLogStore()).isEqualTo(domain.commandLogStores().get(0));
                assertThat(contract.hotProjectionStore()).isEqualTo(domain.hotProjectionStores().get(0));
                assertThat(contract.historyStore()).isEqualTo(domain.historyStores().get(0));
                assertThat(contract.cacheStore()).isEqualTo(domain.cacheStores().get(0));
                assertThat(contract.aggregateScopePrefix()).isEqualTo(command.aggregateScopePrefix());
                assertThat(contract.aggregateIdField()).isEqualTo(command.aggregateIdField());
                assertThat(contract.requiredPayloadFields()).isEqualTo(command.requiredPayloadFields());
                assertThat(contract.allowedPayloadFields()).isEqualTo(command.allowedPayloadFields());
            }
        }
    }

    @Test
    void profileContractsDoNotExposePresenceFields() {
        java.util.Set<String> presenceFields = java.util.Set.of("online", "currentServer", "currentProxy");

        for (String declarationId : List.of("RECORD_PLAYER_LOGIN", "RECORD_PLAYER_LOGOUT")) {
            assertThat(CONTRACTS.get(declarationId).allowedPayloadFields())
                .as(declarationId + " profile payload fields")
                .doesNotContainAnyElementsOf(presenceFields);
            assertThat(AuthorityCommandPayloads.payload(sampleCommand(declarationId)))
                .as(declarationId + " serialized profile payload")
                .doesNotContainKeys("online", "currentServer", "currentProxy");
        }
        assertThat(CONTRACTS.get("START_SESSION").allowedPayloadFields())
            .as("session owns live presence fields")
            .containsAll(presenceFields);
    }

    @Test
    void profilePayloadDecoderRejectsPresenceFields() {
        DataAuthority.AuthorityCommand command = sampleCommand("RECORD_PLAYER_LOGIN");
        Map<String, Object> payload = new java.util.LinkedHashMap<>(AuthorityCommandPayloads.payload(command));
        payload.put("currentServer", "lobby");

        assertThatThrownBy(() -> AuthorityDomainDeclarations.command("RECORD_PLAYER_LOGIN")
            .payloadDecoder()
            .apply(command.manifest(), payload))
            .hasMessageContaining("profile payload must not carry presence field currentServer");
    }

    @Test
    void contractManifestClassifiesSyncAndAsyncCommands() {
        assertThat(CONTRACTS.get("RECORD_PLAYER_LOGIN").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(CONTRACTS.get("RECORD_PLAYER_LOGOUT").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(CONTRACTS.get("RECORD_MATCH_START").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(CONTRACTS.get("RECORD_MATCH_END").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.ASYNC_DURABLE);
        assertThat(CONTRACTS.get("GRANT_RANK").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(CONTRACTS.get("REVOKE_RANK").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(CONTRACTS.get("START_SESSION").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(CONTRACTS.get("RENEW_SESSION").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.SYNC_INTERACTIVE);
        assertThat(CONTRACTS.get("END_SESSION").deliveryMode())
            .isEqualTo(AuthorityCommandManifest.CommandDeliveryMode.SYNC_INTERACTIVE);
    }

    @Test
    void contractManifestClassifiesCompareRequiredCommands() {
        assertThat(CONTRACTS.get("RECORD_PLAYER_LOGIN").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(CONTRACTS.get("RECORD_PLAYER_LOGOUT").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(CONTRACTS.get("START_SESSION").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(CONTRACTS.get("RENEW_SESSION").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(CONTRACTS.get("END_SESSION").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(CONTRACTS.get("RECORD_MATCH_START").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(CONTRACTS.get("RECORD_MATCH_END").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED);
        assertThat(CONTRACTS.get("GRANT_RANK").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.COMPARE_REQUIRED);
        assertThat(CONTRACTS.get("REVOKE_RANK").revisionPolicy())
            .isEqualTo(AuthorityCommandManifest.CommandRevisionPolicy.COMPARE_REQUIRED);
    }

    @Test
    void contractManifestFingerprintIsStable() throws Exception {
        String actual = AuthorityCommandManifest.fingerprint();

        assertThat(actual).matches("[0-9a-f]{64}");
        assertThat(actual).isEqualTo(goldenFingerprint("/contracts/data-authority-command-contract.sha256"));
    }

    @Test
    void routeManifestFingerprintIsStable() throws Exception {
        String actual = AuthorityCommandManifest.routeManifestFingerprint();

        assertThat(actual).matches("[0-9a-f]{64}");
        assertThat(actual).isEqualTo(goldenFingerprint("/contracts/data-authority-command-route-manifest.sha256"));
    }

    @Test
    void routePartitionKeyVectorsCoverEveryDeclaredCommand() {
        Map<String, String> vectors = AuthorityCommandManifest.routePartitionKeyVectors();

        assertThat(vectors.keySet()).containsExactlyInAnyOrderElementsOf(AuthorityDomainDeclarations.declarationIds());
        assertThat(vectors)
            .containsEntry("GRANT_RANK", "rank:player:{aggregateId}=>rank:player:{aggregateId}")
            .containsEntry("RECORD_PLAYER_LOGIN", "player:{aggregateId}=>player:{aggregateId}")
            .containsEntry("START_SESSION", "subject:{aggregateId}=>subject:{aggregateId}")
            .containsEntry("RECORD_MATCH_START", "match:{aggregateId}=>match:{aggregateId}");
    }

    @Test
    void stringLookupRejectsUndeclaredDeclarationId() {
        assertThatThrownBy(() -> AuthorityCommandManifest.declaration("NOT_DECLARED"))
            .hasMessageContaining("No authority command declaration for NOT_DECLARED");
    }

    @Test
    void contractCommandsRoundTripThroughAuthorityLogTransport() {
        Map<String, DataAuthority.AuthorityCommand> received = new LinkedHashMap<>();
        DataAuthority.CommandPort commandPort = new AuthorityPrincipalCommandPort(command -> {
            received.put(command.declarationId(), command);
            return CompletableFuture.completedFuture(acceptedResult(
                command,
                Math.max(1L, command.expectedRevision() + 1L)
            ));
        });
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();
        AuthorityLogCommandProcessor processor = new AuthorityLogCommandProcessor(log, commandPort);

        for (AuthorityCommandManifest.CommandContract contract : CONTRACTS.values()) {
            DataAuthority.AuthorityCommand command = sampleCommand(contract.declarationId());
            AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, command);
            DataAuthority.CommandResult result = processor.process(
                commandRecord,
                writerClaim(command, contract.domain())
            ).toCompletableFuture().join().commandResult();
            DataAuthority.AuthorityCommand decoded = received.get(contract.declarationId());

            assertThat(result.accepted()).as(contract.declarationId() + " result").isTrue();
            assertThat(decoded).as(contract.declarationId() + " decoded command")
                .isInstanceOf(AuthorityDomainDeclarations.command(contract.declarationId()).commandClass());
            assertThat(decoded.actorId()).as(contract.declarationId() + " actor").isEqualTo("contract-test");
            assertThat(AuthorityCommandRoute.fromCommand(decoded).domain()).isEqualTo(contract.domain());
            AuthorityCommandFrame frame = AuthorityCommandFrame.fromCommand(decoded);
            assertThat(frame.manifestPayload())
                .containsEntry("declarationId", contract.declarationId())
                .containsEntry("routeManifestFingerprint", AuthorityCommandManifest.routeManifestFingerprint())
                .doesNotContainKey("commandType");
            assertThat(AuthorityCommandPayloads.payload(frame.toCommand()))
                .isEqualTo(AuthorityCommandPayloads.payload(decoded));
        }
    }

    @Test
    void contractRejectsUnknownTopLevelPayloadFields() {
        DataAuthority.AuthorityCommand command = sampleCommand("GRANT_RANK");
        Map<String, Object> payload = new java.util.LinkedHashMap<>(AuthorityCommandPayloads.payload(command));
        payload.put("legacyCollection", "player_ranks");

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            declarationId(command),
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
        DataAuthority.AuthorityCommand command = sampleCommand("GRANT_RANK");

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            declarationId(command),
            command.actorId(),
            command.scope(),
            command.idempotencyKey(),
            command.deadlineEpochMillis(),
            command.fencingToken(),
            command.expectedRevision(),
            DataAuthority.COMMAND_SCHEMA_VERSION + 1,
            AuthorityCommandRoute.fromCommand(command),
            command.provenance(),
            AuthorityCommandPayloads.payload(command)
        )).hasMessageContaining("schema version");
    }

    @Test
    void contractRejectsRouteDomainDrift() {
        DataAuthority.AuthorityCommand command = sampleCommand("GRANT_RANK");

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            declarationId(command),
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
            AuthorityCommandPayloads.payload(command)
        )).hasMessageContaining("route domain");
    }

    @Test
    void contractRejectsPartitionKeyDrift() {
        DataAuthority.AuthorityCommand command = sampleCommand("GRANT_RANK");

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            declarationId(command),
            command.actorId(),
            command.scope(),
            command.idempotencyKey(),
            command.deadlineEpochMillis(),
            command.fencingToken(),
            command.expectedRevision(),
            DataAuthority.COMMAND_SCHEMA_VERSION,
            new AuthorityCommandRoute("rank", "cmd.rank", "evt.rank",
                "state.rank", "rank:player:" + UUID.randomUUID()),
            command.provenance(),
            AuthorityCommandPayloads.payload(command)
        )).hasMessageContaining("partition key");
    }

    @Test
    void contractRejectsScopePayloadAggregateDrift() {
        DataAuthority.PlayerRankCommand command = (DataAuthority.PlayerRankCommand)
            sampleCommand("GRANT_RANK");
        Map<String, Object> payload = new java.util.LinkedHashMap<>(AuthorityCommandPayloads.payload(command));
        UUID otherPlayerId = UUID.randomUUID();
        payload.put("playerId", otherPlayerId.toString());

        assertThatThrownBy(() -> new AuthorityCommandFrame(
            command.commandId(),
            declarationId(command),
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
            .isInstanceOf(AuthorityCommandManifest.CommandContractViolation.class)
            .hasMessageContaining("scope does not match payload aggregate id")
            .hasMessageContaining("expected rank:player:" + otherPlayerId)
            .hasMessageContaining("but was " + command.scope());
    }

    @Test
    void contractRejectsAnyRevisionForCompareRequiredCommands() {
        DataAuthority.AuthorityCommand command =
            sampleCommand("GRANT_RANK", DataAuthority.ANY_REVISION);

        assertThatThrownBy(() -> AuthorityCommandFrame.fromCommand(command))
            .isInstanceOf(AuthorityCommandManifest.CommandContractViolation.class)
            .hasMessageContaining("requires a concrete expectedRevision")
            .satisfies(exception -> assertThat(
                ((AuthorityCommandManifest.CommandContractViolation) exception).rejectionReason()
            ).isEqualTo(DataAuthority.RejectionReason.STALE_REVISION));
    }

    private static DataAuthority.AuthorityCommand sampleCommand(String type) {
        return sampleCommand(type, expectedRevisionFor(type));
    }

    private static DataAuthority.AuthorityCommand sampleCommand(
        String type,
        long expectedRevision
    ) {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        return switch (type) {
            case "RECORD_PLAYER_LOGIN", "RECORD_PLAYER_LOGOUT" -> new DataAuthority.PlayerProfileCommand(
                manifest(commandId, type, "player:" + playerId, now, expectedRevision),
                playerId,
                "ContractUser",
                now,
                null,
                null,
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
            case "START_SESSION", "RENEW_SESSION", "END_SESSION" -> new DataAuthority.PlayerSessionCommand(
                manifest(commandId, type, "subject:" + playerId, now, expectedRevision),
                playerId,
                "ContractUser",
                UUID.randomUUID(),
                now,
                "lobby-1",
                "proxy-1",
                "127.0.0.1",
                765,
                "END_SESSION".equals(type) ? "quit" : null
            );
            case "GRANT_RANK", "REVOKE_RANK" -> new DataAuthority.PlayerRankCommand(
                manifest(commandId, type, "rank:player:" + playerId, now, expectedRevision),
                playerId,
                "GRANT_RANK".equals(type) ? "ADMIN" : "DEFAULT",
                "GRANT_RANK".equals(type)
                    ? List.of("DEFAULT", "ADMIN")
                    : List.of("DEFAULT")
            );
            case "RECORD_MATCH_START", "RECORD_MATCH_END" -> new DataAuthority.MatchCommand(
                manifest(commandId, type, "match:" + matchId, now, expectedRevision),
                matchId,
                "duels",
                "arena-1",
                "server-1",
                "slot-1",
                "RECORD_MATCH_START".equals(type) ? "STARTED" : "ENDED",
                now,
                "RECORD_MATCH_END".equals(type) ? now + 1000L : null,
                Map.of("variant", "standard"),
                List.of(new DataAuthority.MatchParticipant(
                    playerId,
                    "red",
                    "RECORD_MATCH_END".equals(type) ? 1 : null,
                    "ACTIVE",
                    Map.of("kills", 1)
                ))
            );
            default -> throw new IllegalArgumentException("Unknown command declaration " + type);
        };
    }

    private static long expectedRevisionFor(String type) {
        return switch (type) {
            case "GRANT_RANK", "REVOKE_RANK" -> 1L;
            default -> DataAuthority.ANY_REVISION;
        };
    }

    private static String declarationId(DataAuthority.AuthorityCommand command) {
        return command.declarationId();
    }

    private static DataAuthority.CommandManifest manifest(
        UUID commandId,
        String type,
        String scope,
        long now,
        long expectedRevision
    ) {
        return DataAuthority.CommandManifest.create(
            commandId,
            type,
            "contract-test",
            scope,
            type + ":" + commandId,
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

    private static AuthorityWriterClaim writerClaim(
        DataAuthority.AuthorityCommand command,
        String domain
    ) {
        return AuthorityWriterClaim.mint(
            domain,
            AuthorityCommandRoute.fromCommand(command).commandTopic(),
            AuthorityWriteCustody.fromCommand(command).ownershipPartitionKey(),
            "authority-contract-test",
            1L,
            null,
            0L,
            Instant.EPOCH
        );
    }

    private static DataAuthority.CommandSettlement settlement(
        DataAuthority.AuthorityCommand command,
        long revision
    ) {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        Object aggregateId = AuthorityCommandPayloads.payload(command)
            .get(AuthorityCommandManifest.declaration(command.declarationId()).aggregateIdField());
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "authority-log-test-authority",
            command.scope(),
            projectionFamily(command.declarationId()),
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
            "authority-log-test-authority",
            route.domain(),
            route.commandTopic(),
            route.responseTopic(),
            route.eventTopic(),
            route.stateTopic(),
            route.partitionKey(),
            command.fencingToken(),
            command.idempotencyKey(),
            command.expectedRevision(),
            watermark
        );
    }

    private static String projectionFamily(String type) {
        return switch (type) {
            case "GRANT_RANK", "REVOKE_RANK" -> "player_rank";
            case "RECORD_MATCH_START", "RECORD_MATCH_END" -> "match";
            case "RECORD_PLAYER_LOGIN", "RECORD_PLAYER_LOGOUT" ->
                "player_profile";
            case "START_SESSION", "RENEW_SESSION", "END_SESSION" -> "presence";
            default -> throw new IllegalArgumentException("Unknown command declaration " + type);
        };
    }

    private static String goldenFingerprint(String path) throws Exception {
        try (InputStream input = DataAuthorityCommandContractManifestTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new AssertionError("Missing golden contract fingerprint resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
