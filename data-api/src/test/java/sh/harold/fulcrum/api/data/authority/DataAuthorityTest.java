package sh.harold.fulcrum.api.data.authority;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAuthorityTest {
    @Test
    void authorityCommandDoesNotExposeMapPayload() {
        assertThat(DataAuthority.AuthorityCommand.class.getDeclaredMethods())
            .extracting(java.lang.reflect.Method::getName)
            .doesNotContain("payload");
    }

    @Test
    void typedRankCommandExposesTypedFields() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                commandId.toString(),
                System.currentTimeMillis() + 1000,
                "12",
                4L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );

        assertThat(command.commandId()).isEqualTo(commandId);
        assertThat(command.expectedRevision()).isEqualTo(4L);
        assertThat(command.playerId()).isEqualTo(playerId);
        assertThat(command.primaryRank()).isEqualTo("ADMIN");
        assertThat(command.ranks()).isEqualTo(List.of("DEFAULT", "ADMIN"));
        assertThatThrownBy(() -> command.ranks().add("DEFAULT"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void manifestSupportsExplicitAnyRevision() {
        DataAuthority.CommandManifest manifest = DataAuthority.CommandManifest.create(
            UUID.randomUUID(),
            DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
            "paper-runtime",
            "player:" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            System.currentTimeMillis() + 1000,
            "",
            DataAuthority.ANY_REVISION
        );

        assertThat(manifest.expectedRevision()).isEqualTo(DataAuthority.ANY_REVISION);
        assertThat(manifest.schemaVersion()).isEqualTo(DataAuthority.COMMAND_SCHEMA_VERSION);
        assertThat(manifest.provenance().providerKind()).isEqualTo("unknown");
    }

    @Test
    void manifestCarriesOptionalProvenance() {
        DataAuthority.CommandManifest manifest = DataAuthority.CommandManifest.create(
            UUID.randomUUID(),
            DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
            "paper-runtime",
            "player:" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            System.currentTimeMillis() + 1000,
            "",
            DataAuthority.ANY_REVISION,
            new DataAuthority.CommandProvenance(
                "paper-1",
                "messagebus:paper-1->registry-service",
                "message-bus-client",
                2,
                "node:paper-1"
            )
        );

        assertThat(manifest.provenance().originNode()).isEqualTo("paper-1");
        assertThat(manifest.provenance().authorityRoute()).isEqualTo("messagebus:paper-1->registry-service");
        assertThat(manifest.provenance().providerKind()).isEqualTo("message-bus-client");
        assertThat(manifest.provenance().contractVersion()).isEqualTo(2);
        assertThat(manifest.provenance().verifiedPrincipal()).isEqualTo("node:paper-1");
    }

    @Test
    void commandManifestRequiresAuthorityFields() {
        assertThatThrownBy(() -> DataAuthority.CommandManifest.create(
            UUID.randomUUID(),
            DataAuthority.CommandType.START_SESSION,
            "",
            "player:" + UUID.randomUUID(),
            "session-1",
            System.currentTimeMillis() + 1000,
            "",
            0L
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actorId");
    }

    @Test
    void profileSnapshotCopiesProfileData() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            null,
            true,
            "lobby-1",
            "proxy-1",
            1000L,
            Map.of("lastWorld", "world"),
            3L
        );

        assertThat(snapshot.normalizedUsername()).isEqualTo("notch");
        assertThat(snapshot.profileData()).containsEntry("lastWorld", "world");
        assertThatThrownBy(() -> snapshot.profileData().put("lastWorld", "nether"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rankSnapshotDefaultsMissingRanks() {
        UUID playerId = UUID.randomUUID();

        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            null,
            2L
        );

        assertThat(snapshot.ranks()).containsExactly("ADMIN");
    }

    @Test
    void snapshotWatermarkCarriesSourceLineage() {
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
            17,
            3L,
            "state-fingerprint",
            "event-chain-hash"
        );

        assertThat(watermark.watermarked()).isTrue();
        assertThat(watermark.logPositioned()).isTrue();
        assertThat(watermark.staleAt(1235L, 1000L)).isFalse();
        assertThat(watermark.visibilityToken().payload())
            .containsEntry("sourcePartition", 17)
            .containsEntry("sourceOffset", 3L)
            .containsEntry("sourceRevision", 4L);
        assertThat(watermark.payload())
            .containsEntry("sourceCommandId", commandId.toString())
            .containsEntry("sourceEventId", eventId.toString())
            .containsEntry("stateTopic", "state.player_rank")
            .containsEntry("sourcePartition", 17)
            .containsEntry("sourceOffset", 3L)
            .containsEntry("watermarked", true);

        DataAuthority.SnapshotWatermark roundTrip = DataAuthority.SnapshotWatermark.fromPayload(
            watermark.payload(),
            DataAuthority.SnapshotWatermark.unwatermarked("rank:player:" + playerId, "player_rank", playerId.toString(), 0L)
        );
        assertThat(roundTrip).isEqualTo(watermark);
    }

    @Test
    void readVisibilityTokenBindsScopeTopicPartitionOffsetAndLineage() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            4L,
            1234L,
            17,
            3L,
            "state-fingerprint",
            "event-chain-hash"
        );

        DataAuthority.ReadVisibilityToken exact = watermark.visibilityToken();
        DataAuthority.ReadVisibilityToken laterOffset = new DataAuthority.ReadVisibilityToken(
            exact.aggregateScope(),
            exact.stateTopic(),
            exact.partitionKey(),
            exact.sourcePartition(),
            exact.sourceOffset() + 1L,
            exact.sourceRevision(),
            exact.eventChainHash()
        );

        assertThat(watermark.satisfies(exact)).isTrue();
        assertThat(watermark.satisfies(laterOffset)).isFalse();
        assertThat(DataAuthority.ReadRequirement.after(exact).minimumRevision()).isEqualTo(4L);
    }

    @Test
    void projectionDeliveryReceiptCarriesWatermarkProof() {
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
            UUID.randomUUID(),
            eventId,
            4L,
            1234L,
            9,
            3L,
            "state-fingerprint",
            "event-chain-hash"
        );

        DataAuthority.ProjectionDeliveryReceipt receipt =
            DataAuthority.ProjectionDeliveryReceipt.fromWatermark("player_rank", watermark);

        assertThat(receipt.delivered()).isTrue();
        assertThat(receipt.satisfies("player_rank", "rank:player:" + playerId, 4L)).isTrue();
        assertThat(receipt.payload())
            .containsEntry("sourceEventId", eventId.toString())
            .containsEntry("sourcePartition", 9)
            .containsEntry("sourceOffset", 3L)
            .containsEntry("outputFingerprint", "state-fingerprint")
            .containsEntry("lineageFingerprint", "event-chain-hash");
        assertThat(receipt.logPositioned()).isTrue();

        DataAuthority.ProjectionDeliveryReceipt roundTrip =
            DataAuthority.ProjectionDeliveryReceipt.fromPayload(receipt.payload(), null);
        assertThat(roundTrip).isEqualTo(receipt);
    }

    @Test
    void commandSettlementCarriesRouteFenceAndWatermark() {
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
            7L,
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
            6L,
            watermark
        );

        assertThat(settlement.settled()).isTrue();
        assertThat(settlement.payload())
            .containsEntry("commandTopic", "cmd.player_rank")
            .containsEntry("responseTopic", "rsp.player_rank")
            .containsEntry("fencingToken", "12")
            .containsEntry("idempotencyKey", "rank:" + commandId)
            .containsEntry("settled", true);

        DataAuthority.CommandSettlement roundTrip = DataAuthority.CommandSettlement.fromPayload(
            settlement.payload(),
            DataAuthority.CommandSettlement.unsettled(0L)
        );
        assertThat(roundTrip).isEqualTo(settlement);
    }

    @Test
    void readQuoteCarriesSourceProvenancePayload() {
        DataAuthority.AuthorityBootIdentity bootIdentity = new DataAuthority.AuthorityBootIdentity(
            "registry-1",
            "authority-1",
            "startup-fingerprint",
            900L,
            "message-bus-provider",
            "read-contract"
        );
        DataAuthority.ReadProvenance provenance = DataAuthority.ReadProvenance.cache(
            bootIdentity,
            1_000L,
            1_250L,
            5_000L
        );
        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            "rank:player:" + UUID.randomUUID(),
            "player_rank",
            4L,
            4L,
            DataAuthority.ReadQuoteStatus.SATISFIED,
            null,
            null,
            provenance
        );

        assertThat(quote.provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(quote.provenance().cacheAgeMillis()).isEqualTo(250L);
        assertThat(quote.provenance().expired()).isFalse();
        assertThat(quote.provenance().authorityBoot()).isEqualTo(bootIdentity);

        DataAuthority.ReadQuote roundTrip = DataAuthority.ReadQuote.fromPayload(quote.payload(), null);
        assertThat(roundTrip).isEqualTo(quote);
        assertThat(roundTrip.provenance().authorityBoot()).isEqualTo(bootIdentity);
        assertThat(roundTrip.payload()).containsEntry("satisfied", true);
    }

    @Test
    void rankReaderDefaultQuoteReportsReadStatus() {
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

        DataAuthority.PlayerRankReader reader = id ->
            CompletableFuture.completedFuture(id.equals(playerId) ? Optional.of(snapshot) : Optional.empty());

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = reader
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).contains(snapshot);
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.SATISFIED);
        assertThat(read.quote().requiredRevision()).isEqualTo(4L);
        assertThat(read.quote().observedRevision()).isEqualTo(4L);
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().satisfies("player_rank", "rank:player:" + playerId, 4L))
            .isTrue();
    }

    @Test
    void rankReaderDefaultQuoteRejectsUnsatisfiedVisibilityToken() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            "rank:player:" + playerId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            4L,
            1234L,
            17,
            3L,
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
        DataAuthority.ReadVisibilityToken laterOffset = new DataAuthority.ReadVisibilityToken(
            watermark.aggregateScope(),
            watermark.stateTopic(),
            watermark.partitionKey(),
            watermark.sourcePartition(),
            watermark.sourceOffset() + 1L,
            watermark.sourceRevision(),
            watermark.eventChainHash()
        );
        DataAuthority.PlayerRankReader reader = id ->
            CompletableFuture.completedFuture(id.equals(playerId) ? Optional.of(snapshot) : Optional.empty());

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = reader
            .quoteRanks(playerId, DataAuthority.ReadRequirement.after(laterOffset))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.VISIBILITY_TOKEN_MISMATCH);
    }

    @Test
    void missingRequiredRankQuoteIsUnknownOrStale() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankReader reader = id -> CompletableFuture.completedFuture(Optional.empty());

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = reader
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE);
        assertThat(read.quote().retryable()).isTrue();
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);
    }

    @Test
    void profileReaderDefaultQuoteReportsReadStatus() {
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

        DataAuthority.PlayerProfileReader reader = id ->
            CompletableFuture.completedFuture(id.equals(playerId) ? Optional.of(snapshot) : Optional.empty());

        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> read = reader
            .quoteProfile(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).contains(snapshot);
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.SATISFIED);
        assertThat(read.quote().requiredRevision()).isEqualTo(4L);
        assertThat(read.quote().observedRevision()).isEqualTo(4L);
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().satisfies("player_profile", "player:" + playerId, 4L))
            .isTrue();
    }

    @Test
    void profileReaderCanCheckExistence() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            "notch",
            false,
            null,
            null,
            0L,
            Map.of(),
            1L
        );

        DataAuthority.PlayerProfileReader reader = id ->
            CompletableFuture.completedFuture(id.equals(playerId) ? Optional.of(snapshot) : Optional.empty());

        assertThat(reader.profileExists(playerId).toCompletableFuture().join()).isTrue();
        assertThat(reader.profileExists(UUID.randomUUID()).toCompletableFuture().join()).isFalse();
    }
}
