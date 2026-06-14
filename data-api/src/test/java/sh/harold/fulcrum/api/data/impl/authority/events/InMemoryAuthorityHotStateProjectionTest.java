package sh.harold.fulcrum.api.data.impl.authority.events;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuthorityHotStateProjectionTest {
    @Test
    void manifestDeclaresHotStateEventSurface() {
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();

        AuthorityProjectionManifest manifest = projection.projectionManifest();

        assertThat(manifest.projectionName()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_NAME);
        assertThat(manifest.projectionVersion()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_VERSION);
        assertThat(manifest.acceptedEventTypes()).containsExactly(
            "END_SESSION",
            "GRANT_RANK",
            "RECORD_PLAYER_LOGIN",
            "RECORD_PLAYER_LOGOUT",
            "RENEW_SESSION",
            "REVOKE_RANK",
            "START_SESSION"
        );
        assertThat(manifest.acceptsEventType("RECORD_MATCH_START")).isFalse();
    }

    @Test
    void profileEventsProjectPresenceWithDeliveryReceipt() {
        UUID playerId = UUID.randomUUID();
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();

        AuthorityEventDispatchResult login = projection.dispatch(profileEvent(
            playerId,
            "START_SESSION",
            1L,
            payload(
                "playerId", playerId.toString(),
                "username", "Richa",
                "currentServer", "lobby",
                "currentProxy", "proxy-a",
                "sessionId", UUID.randomUUID().toString()
            )
        ));
        AuthorityEventDispatchResult renewal = projection.dispatch(profileEvent(
            playerId,
            "RENEW_SESSION",
            2L,
            payload(
                "playerId", playerId.toString(),
                "username", "Richa",
                "currentServer", "survival",
                "currentProxy", "proxy-a"
            )
        ));
        AuthorityEventDispatchResult logout = projection.dispatch(profileEvent(
            playerId,
            "END_SESSION",
            3L,
            payload(
                "playerId", playerId.toString(),
                "username", "Richa",
                "disconnectReason", "quit"
            )
        ));

        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> read =
            projection.quoteProfile(playerId, DataAuthority.ReadRequirement.atLeast(3L))
                .toCompletableFuture()
                .join();

        assertThat(login.successful()).isTrue();
        assertThat(renewal.successful()).isTrue();
        assertThat(logout.successful()).isTrue();
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.username()).isEqualTo("Richa");
            assertThat(snapshot.online()).isFalse();
            assertThat(snapshot.currentServer()).isNull();
            assertThat(snapshot.currentProxy()).isNull();
            assertThat(snapshot.profileData()).containsEntry("disconnectReason", "quit");
            assertThat(snapshot.revision()).isEqualTo(3L);
            assertThat(snapshot.watermark().stateTopic()).isEqualTo("state.player_profile");
        });
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().delivered()).isTrue();
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        DataAuthorityReadContracts.validateQuote(
            read.quote(),
            "player:" + playerId,
            "player_profile",
            DataAuthority.ReadRequirement.atLeast(3L)
        );
    }

    @Test
    void rankEventsProjectEffectiveRanksWithDeliveryReceipt() {
        UUID playerId = UUID.randomUUID();
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();

        AuthorityEventDispatchResult result = projection.dispatch(rankEvent(
            playerId,
            "GRANT_RANK",
            1L,
            payload(
                "playerId", playerId.toString(),
                "primaryRank", "ADMIN",
                "ranks", List.of("DEFAULT", "ADMIN")
            )
        ));

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read =
            projection.quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(1L))
                .toCompletableFuture()
                .join();

        assertThat(result.successful()).isTrue();
        assertThat(result.projectionVersion()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_VERSION);
        assertThat(result.outputFingerprint()).matches("[0-9a-f]{64}");
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.primaryRank()).isEqualTo("ADMIN");
            assertThat(snapshot.ranks()).containsExactly("DEFAULT", "ADMIN");
            assertThat(snapshot.watermark().aggregateScope()).isEqualTo("rank:player:" + playerId);
            assertThat(snapshot.watermark().stateTopic()).isEqualTo("state.player_rank");
        });
        DataAuthorityReadContracts.validateQuote(
            read.quote(),
            "rank:player:" + playerId,
            "player_rank",
            DataAuthority.ReadRequirement.atLeast(1L)
        );
    }

    @Test
    void replayDoesNotMutateLiveHotState() {
        UUID playerId = UUID.randomUUID();
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();
        projection.dispatch(rankEvent(
            playerId,
            "GRANT_RANK",
            1L,
            payload("playerId", playerId.toString(), "primaryRank", "ADMIN", "ranks", List.of("DEFAULT", "ADMIN"))
        ));

        AuthorityEventReplayResult replay = projection.replay(rankEvent(
            playerId,
            "REVOKE_RANK",
            2L,
            payload("playerId", playerId.toString(), "primaryRank", "DEFAULT", "ranks", List.of("DEFAULT"))
        ));

        DataAuthority.PlayerRankSnapshot liveSnapshot =
            projection.findRanks(playerId).toCompletableFuture().join().orElseThrow();
        assertThat(replay.projectionVersion()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_VERSION);
        assertThat(replay.outputFingerprint()).matches("[0-9a-f]{64}");
        assertThat(liveSnapshot.primaryRank()).isEqualTo("ADMIN");
        assertThat(liveSnapshot.revision()).isEqualTo(1L);
    }

    @Test
    void eventLogRebuildsEquivalentHotStateProjection() {
        UUID playerId = UUID.randomUUID();
        AuthorityEventEnvelope grant = rankEvent(
            playerId,
            "GRANT_RANK",
            1L,
            payload("playerId", playerId.toString(), "primaryRank", "ADMIN", "ranks", List.of("DEFAULT", "ADMIN"))
        );
        AuthorityEventEnvelope revoke = rankEvent(
            playerId,
            "REVOKE_RANK",
            2L,
            payload("playerId", playerId.toString(), "primaryRank", "DEFAULT", "ranks", List.of("DEFAULT"))
        );
        List<AuthorityEventEnvelope> eventLog = List.of(grant, revoke);
        InMemoryAuthorityHotStateProjection liveProjection = new InMemoryAuthorityHotStateProjection();
        InMemoryAuthorityHotStateProjection rebuiltProjection = new InMemoryAuthorityHotStateProjection();

        eventLog.forEach(liveProjection::dispatch);
        eventLog.forEach(rebuiltProjection::dispatch);

        DataAuthority.PlayerRankSnapshot liveSnapshot =
            liveProjection.findRanks(playerId).toCompletableFuture().join().orElseThrow();
        DataAuthority.PlayerRankSnapshot rebuiltSnapshot =
            rebuiltProjection.findRanks(playerId).toCompletableFuture().join().orElseThrow();
        assertThat(rebuiltSnapshot).isEqualTo(liveSnapshot);
        assertThat(rebuiltSnapshot.revision()).isEqualTo(2L);
        assertThat(rebuiltSnapshot.watermark().sourceEventId()).isEqualTo(revoke.eventId());
        assertThat(rebuiltSnapshot.watermark().stateTopic()).isEqualTo("state.player_rank");
    }

    @Test
    void quotedReadRejectsStaleRequirement() {
        UUID playerId = UUID.randomUUID();
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();
        projection.dispatch(rankEvent(
            playerId,
            "GRANT_RANK",
            1L,
            payload("playerId", playerId.toString(), "primaryRank", "ADMIN", "ranks", List.of("DEFAULT", "ADMIN"))
        ));

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read =
            projection.quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(2L))
                .toCompletableFuture()
                .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.STALE_REVISION);
        assertThat(read.quote().observedRevision()).isEqualTo(1L);
        assertThat(read.quote().deliveryReceipt().deliveredRevision()).isEqualTo(1L);
    }

    private static AuthorityEventEnvelope profileEvent(
        UUID playerId,
        String eventType,
        long revision,
        Map<String, Object> payload
    ) {
        return event(playerId, eventType, revision, "player_profile", "player:" + playerId, payload);
    }

    private static AuthorityEventEnvelope rankEvent(
        UUID playerId,
        String eventType,
        long revision,
        Map<String, Object> payload
    ) {
        return event(playerId, eventType, revision, "player_rank", "rank:player:" + playerId, payload);
    }

    private static AuthorityEventEnvelope event(
        UUID playerId,
        String eventType,
        long revision,
        String aggregateType,
        String aggregateScope,
        Map<String, Object> payload
    ) {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("commandId", commandId.toString());
        eventPayload.put("commandType", eventType);
        eventPayload.put("scope", aggregateScope);
        eventPayload.put("revision", revision);
        eventPayload.put("route", route(aggregateType, aggregateScope));
        eventPayload.put("payload", payload);
        return new AuthorityEventEnvelope(
            UUID.randomUUID(),
            commandId,
            aggregateScope,
            aggregateType,
            playerId.toString(),
            revision,
            eventType,
            eventPayload,
            payload("authorityProvider", "postgres"),
            Instant.ofEpochMilli(10_000L + revision)
        );
    }

    private static Map<String, Object> route(String aggregateType, String aggregateScope) {
        return payload(
            "domain", aggregateType,
            "commandTopic", "cmd." + aggregateType,
            "eventTopic", "evt." + aggregateType,
            "stateTopic", "state." + aggregateType,
            "partitionKey", aggregateScope
        );
    }

    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index].toString(), pairs[index + 1]);
        }
        return values;
    }
}
