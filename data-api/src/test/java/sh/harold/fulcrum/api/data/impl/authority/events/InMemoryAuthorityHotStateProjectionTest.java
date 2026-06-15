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
        AuthorityStateRestoreTarget restoreTarget = projection;

        assertThat(manifest.projectionName()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_NAME);
        assertThat(manifest.projectionVersion()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_VERSION);
        assertThat(restoreTarget.projectionName()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_NAME);
        assertThat(restoreTarget.projectionVersion()).isEqualTo(InMemoryAuthorityHotStateProjection.PROJECTION_VERSION);
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
        assertThat(manifest.acceptsEventType("START_SESSION")).isTrue();
    }

    @Test
    void profileEventsProjectProfileWithDeliveryReceipt() {
        UUID playerId = UUID.randomUUID();
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();

        AuthorityEventDispatchResult login = projection.dispatch(profileEvent(
            playerId,
            "RECORD_PLAYER_LOGIN",
            1L,
            payload(
                "playerId", playerId.toString(),
                "username", "Richa",
                "currentServer", "lobby",
                "currentProxy", "proxy-a"
            )
        ));
        AuthorityEventDispatchResult logout = projection.dispatch(profileEvent(
            playerId,
            "RECORD_PLAYER_LOGOUT",
            2L,
            payload(
                "playerId", playerId.toString(),
                "username", "Richa",
                "disconnectReason", "quit"
            )
        ));

        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> read =
            projection.quoteProfile(playerId, DataAuthority.ReadRequirement.atLeast(2L))
                .toCompletableFuture()
                .join();

        assertThat(login.successful()).isTrue();
        assertThat(logout.successful()).isTrue();
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.username()).isEqualTo("Richa");
            assertThat(snapshot.online()).isFalse();
            assertThat(snapshot.currentServer()).isNull();
            assertThat(snapshot.currentProxy()).isNull();
            assertThat(snapshot.profileData()).containsEntry("disconnectReason", "quit");
            assertThat(snapshot.revision()).isEqualTo(2L);
            assertThat(snapshot.watermark().stateTopic()).isEqualTo("state.player_profile");
        });
        assertThat(read.quote().deliveryReceipt()).isNotNull();
        assertThat(read.quote().deliveryReceipt().delivered()).isTrue();
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.HOT_STATE);
        DataAuthorityReadContracts.validateQuote(
            read.quote(),
            "player:" + playerId,
            "player_profile",
            DataAuthority.ReadRequirement.atLeast(2L)
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
            assertThat(snapshot.watermark().logPositioned()).isTrue();
            DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> tokenRead =
                projection.quoteRanks(playerId, DataAuthority.ReadRequirement.after(snapshot.watermark().visibilityToken()))
                    .toCompletableFuture()
                    .join();
            assertThat(tokenRead.satisfied()).isTrue();
            assertThat(tokenRead.snapshot()).contains(snapshot);
        });
        DataAuthorityReadContracts.validateQuote(
            read.quote(),
            "rank:player:" + playerId,
            "player_rank",
            DataAuthority.ReadRequirement.atLeast(1L)
        );
    }

    @Test
    void sessionEventsProjectPresenceWithDeliveryReceipt() {
        UUID subjectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();

        AuthorityEventDispatchResult start = projection.dispatch(presenceEvent(
            subjectId,
            "START_SESSION",
            3L,
            payload(
                "subjectId", subjectId.toString(),
                "playerId", subjectId.toString(),
                "username", "Richa",
                "sessionId", sessionId.toString(),
                "currentServer", "lobby",
                "currentProxy", "proxy-a",
                "timestamp", 1234L
            )
        ));

        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> read =
            projection.quotePresence(subjectId, DataAuthority.ReadRequirement.atLeast(3L))
                .toCompletableFuture()
                .join();

        assertThat(start.successful()).isTrue();
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.subjectId()).isEqualTo(subjectId);
            assertThat(snapshot.playerId()).isEqualTo(subjectId);
            assertThat(snapshot.online()).isTrue();
            assertThat(snapshot.currentServer()).isEqualTo("lobby");
            assertThat(snapshot.currentProxy()).isEqualTo("proxy-a");
            assertThat(snapshot.sessionId()).isEqualTo(sessionId);
            assertThat(snapshot.observedAtEpochMillis()).isEqualTo(1234L);
            assertThat(snapshot.watermark().aggregateScope()).isEqualTo(DataAuthority.Subject.SCOPE_PREFIX + subjectId);
            assertThat(snapshot.watermark().stateTopic()).isEqualTo("state.session");
        });
        DataAuthorityReadContracts.validateQuote(
            read.quote(),
            DataAuthority.Subject.SCOPE_PREFIX + subjectId,
            "presence",
            DataAuthority.ReadRequirement.atLeast(3L)
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
    void compactedRankStateRecordRebuildsHotStateProjection() {
        UUID playerId = UUID.randomUUID();
        Map<String, Object> statePayload = payload(
            "playerId", playerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN"),
            "revision", 4L
        );
        AuthorityStateRecord record = stateRecord(
            playerId,
            "rank:player:" + playerId,
            "player_rank",
            4L,
            "rank",
            "state.rank",
            statePayload
        );
        InMemoryAuthorityHotStateProjection rebuiltProjection = new InMemoryAuthorityHotStateProjection();

        AuthorityStateRestoreResult result = rebuiltProjection.restore(record);
        AuthorityStateRestoreResult idempotent = rebuiltProjection.restore(record);

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read =
            rebuiltProjection.quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
                .toCompletableFuture()
                .join();
        assertThat(result.restored()).isTrue();
        assertThat(idempotent.restored()).isTrue();
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.primaryRank()).isEqualTo("ADMIN");
            assertThat(snapshot.ranks()).containsExactly("DEFAULT", "ADMIN");
            assertThat(snapshot.revision()).isEqualTo(4L);
            assertThat(snapshot.watermark().commandDomain()).isEqualTo("rank");
            assertThat(snapshot.watermark().stateTopic()).isEqualTo("state.rank");
            assertThat(snapshot.watermark().sourcePartition()).isEqualTo(record.sourcePartition());
            assertThat(snapshot.watermark().sourceOffset()).isEqualTo(record.sourceOffset());
            assertThat(snapshot.watermark().stateFingerprint()).isEqualTo(record.stateFingerprint());
        });
        DataAuthorityReadContracts.validateQuote(
            read.quote(),
            "rank:player:" + playerId,
            "player_rank",
            DataAuthority.ReadRequirement.atLeast(4L)
        );
    }

    @Test
    void compactedProfileStateRecordRebuildsHotStateProjection() {
        UUID playerId = UUID.randomUUID();
        Map<String, Object> profileData = payload(
            "lastIp", "127.0.0.1",
            "lastWorld", "spawn"
        );
        Map<String, Object> statePayload = payload(
            "playerId", playerId.toString(),
            "username", "Richa",
            "normalizedUsername", "richa",
            "online", true,
            "currentServer", "lobby",
            "currentProxy", "proxy-a",
            "totalPlaytimeMs", 1200L,
            "profileData", profileData,
            "revision", 3L
        );
        AuthorityStateRecord record = stateRecord(
            playerId,
            "player:" + playerId,
            "player_profile",
            3L,
            "player",
            "state.player",
            statePayload
        );
        InMemoryAuthorityHotStateProjection rebuiltProjection = new InMemoryAuthorityHotStateProjection();

        AuthorityStateRestoreResult result = rebuiltProjection.restore(record);

        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> read =
            rebuiltProjection.quoteProfile(playerId, DataAuthority.ReadRequirement.atLeast(3L))
                .toCompletableFuture()
                .join();
        assertThat(result.restored()).isTrue();
        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.username()).isEqualTo("Richa");
            assertThat(snapshot.online()).isFalse();
            assertThat(snapshot.currentServer()).isNull();
            assertThat(snapshot.currentProxy()).isNull();
            assertThat(snapshot.profileData()).containsEntry("lastWorld", "spawn");
            assertThat(snapshot.profileData()).doesNotContainKeys("online", "currentServer", "currentProxy");
            assertThat(snapshot.watermark().commandDomain()).isEqualTo("player");
            assertThat(snapshot.watermark().stateTopic()).isEqualTo("state.player");
            assertThat(snapshot.watermark().sourcePartition()).isEqualTo(record.sourcePartition());
            assertThat(snapshot.watermark().sourceOffset()).isEqualTo(record.sourceOffset());
        });
        DataAuthorityReadContracts.validateQuote(
            read.quote(),
            "player:" + playerId,
            "player_profile",
            DataAuthority.ReadRequirement.atLeast(3L)
        );
    }

    @Test
    void compactedStateRestoreRejectsFingerprintMismatch() {
        UUID playerId = UUID.randomUUID();
        Map<String, Object> statePayload = payload(
            "playerId", playerId.toString(),
            "primaryRank", "ADMIN",
            "ranks", List.of("DEFAULT", "ADMIN"),
            "revision", 1L
        );
        AuthorityStateRecord record = new AuthorityStateRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            1L,
            "rank",
            "state.rank",
            "rank:player:" + playerId,
            statePayload,
            "0".repeat(64),
            "1".repeat(64),
            Instant.ofEpochMilli(10_001L)
        );
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();

        AuthorityStateRestoreResult result = projection.restore(record);

        assertThat(result.restored()).isFalse();
        assertThat(result.message()).contains("fingerprint mismatch");
        assertThat(projection.findRanks(playerId).toCompletableFuture().join()).isEmpty();
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

    @Test
    void quotedReadRejectsUnsatisfiedVisibilityToken() {
        UUID playerId = UUID.randomUUID();
        InMemoryAuthorityHotStateProjection projection = new InMemoryAuthorityHotStateProjection();
        projection.dispatch(rankEvent(
            playerId,
            "GRANT_RANK",
            1L,
            payload("playerId", playerId.toString(), "primaryRank", "ADMIN", "ranks", List.of("DEFAULT", "ADMIN"))
        ));
        DataAuthority.PlayerRankSnapshot snapshot =
            projection.findRanks(playerId).toCompletableFuture().join().orElseThrow();
        DataAuthority.ReadVisibilityToken laterOffset = new DataAuthority.ReadVisibilityToken(
            snapshot.watermark().aggregateScope(),
            snapshot.watermark().stateTopic(),
            snapshot.watermark().partitionKey(),
            snapshot.watermark().sourcePartition(),
            snapshot.watermark().sourceOffset() + 1L,
            snapshot.watermark().sourceRevision(),
            snapshot.watermark().eventChainHash()
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read =
            projection.quoteRanks(playerId, DataAuthority.ReadRequirement.after(laterOffset))
                .toCompletableFuture()
                .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.VISIBILITY_TOKEN_MISMATCH);
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

    private static AuthorityEventEnvelope presenceEvent(
        UUID subjectId,
        String eventType,
        long revision,
        Map<String, Object> payload
    ) {
        return event(subjectId, eventType, revision, "presence", DataAuthority.Subject.SCOPE_PREFIX + subjectId, payload);
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
        eventPayload.put("declarationId", eventType);
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
        String domain = "presence".equals(aggregateType) ? "session" : aggregateType;
        String stateTopic = "presence".equals(aggregateType) ? "state.session" : "state." + aggregateType;
        return payload(
            "domain", domain,
            "commandTopic", "cmd." + domain,
            "eventTopic", "evt." + domain,
            "stateTopic", stateTopic,
            "partitionKey", aggregateScope
        );
    }

    private static AuthorityStateRecord stateRecord(
        UUID aggregateId,
        String aggregateScope,
        String aggregateType,
        long revision,
        String commandDomain,
        String stateTopic,
        Map<String, Object> statePayload
    ) {
        return new AuthorityStateRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            aggregateScope,
            aggregateType,
            aggregateId.toString(),
            revision,
            commandDomain,
            stateTopic,
            aggregateScope,
            statePayload,
            AuthorityStateRecord.stateFingerprint(statePayload),
            "1".repeat(64),
            Instant.ofEpochMilli(10_000L + revision)
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
