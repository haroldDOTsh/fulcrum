package sh.harold.fulcrum.api.data.valkey;

import io.lettuce.core.RedisURI;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotCacheCodec;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotCacheStore;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotInvalidation;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ValkeyAuthoritySnapshotCacheStoreTest {
    private static final DockerImageName VALKEY_IMAGE = DockerImageName.parse("valkey/valkey:7.2-alpine");

    @Test
    void cacheKeyUsesSharedValkeySnapshotNamespace() {
        UUID playerId = UUID.randomUUID();
        String scope = "rank:player:" + playerId;

        String key = ValkeyAuthoritySnapshotCacheStore.cacheKey(
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            scope
        );

        assertThat(key)
            .isEqualTo(AuthoritySnapshotCacheCodec.cacheKey(AuthoritySnapshotInvalidation.PLAYER_RANK, scope))
            .startsWith("fulcrum:authority:valkey:snapshot:player_rank:");
    }

    @Test
    void rankLineRoundTripsThroughHashFields() {
        UUID playerId = UUID.randomUUID();
        FakeHashCommands commands = new FakeHashCommands();
        ValkeyAuthoritySnapshotCacheStore store = new ValkeyAuthoritySnapshotCacheStore(
            commands,
            Duration.ofSeconds(2)
        );
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, 1_000L);

        store.writeRank(AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            "rank:player:" + playerId,
            snapshot.revision(),
            snapshot.watermark(),
            snapshot,
            1_250L,
            DataAuthority.ReadProvenance.hotState()
        ));

        Optional<AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerRankSnapshot>> read =
            store.readRank("rank:player:" + playerId);

        assertThat(read).isPresent();
        assertThat(read.get().snapshot()).isEqualTo(snapshot);
        assertThat(read.get().cachedAtEpochMillis()).isEqualTo(1_250L);
        assertThat(read.get().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(commands.ttlMillis()).isEqualTo(2_000L);
    }

    @Test
    void presenceLineRoundTripsThroughHashFields() {
        UUID subjectId = UUID.randomUUID();
        String scope = DataAuthority.Subject.SCOPE_PREFIX + subjectId;
        FakeHashCommands commands = new FakeHashCommands();
        ValkeyAuthoritySnapshotCacheStore store = new ValkeyAuthoritySnapshotCacheStore(
            commands,
            Duration.ofSeconds(2)
        );
        DataAuthority.PlayerPresenceSnapshot snapshot = presenceSnapshot(subjectId, 4L, 1_000L);

        store.writePresence(AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
            scope,
            snapshot.revision(),
            snapshot.watermark(),
            snapshot,
            1_250L,
            DataAuthority.ReadProvenance.hotState()
        ));

        Optional<AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerPresenceSnapshot>> read =
            store.readPresence(scope);

        assertThat(read).isPresent();
        assertThat(read.get().snapshot()).isEqualTo(snapshot);
        assertThat(read.get().cachedAtEpochMillis()).isEqualTo(1_250L);
        assertThat(read.get().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(commands.ttlMillis()).isEqualTo(2_000L);
    }

    @Test
    void olderWriteCannotReplaceNewerLine() {
        UUID playerId = UUID.randomUUID();
        String scope = "rank:player:" + playerId;
        FakeHashCommands commands = new FakeHashCommands();
        ValkeyAuthoritySnapshotCacheStore store = new ValkeyAuthoritySnapshotCacheStore(commands, Duration.ofSeconds(2));
        DataAuthority.PlayerRankSnapshot newer = rankSnapshot(playerId, "ADMIN", 5L, 1_001L);
        DataAuthority.PlayerRankSnapshot older = rankSnapshot(playerId, "VIP", 4L, 1_000L);

        store.writeRank(line(scope, newer, 1_250L));
        store.writeRank(line(scope, older, 1_300L));

        assertThat(store.readRank(scope))
            .get()
            .extracting(AuthoritySnapshotCacheStore.SnapshotLine::snapshot)
            .isEqualTo(newer);
    }

    @Test
    void invalidationRemovesOnlyLinesAtOrBelowRevision() {
        UUID playerId = UUID.randomUUID();
        String scope = "rank:player:" + playerId;
        FakeHashCommands commands = new FakeHashCommands();
        ValkeyAuthoritySnapshotCacheStore store = new ValkeyAuthoritySnapshotCacheStore(commands, Duration.ofSeconds(2));
        DataAuthority.PlayerRankSnapshot newer = rankSnapshot(playerId, "ADMIN", 5L, 1_001L);

        store.writeRank(line(scope, newer, 1_250L));
        store.invalidateRank(scope, 4L, 1_300L);
        assertThat(store.readRank(scope)).isPresent();

        store.invalidateRank(scope, 5L, 1_301L);
        assertThat(store.readRank(scope)).isEmpty();
    }

    @Test
    void corruptHashPayloadIsRefusedAsCacheMiss() {
        UUID playerId = UUID.randomUUID();
        String scope = "rank:player:" + playerId;
        FakeHashCommands commands = new FakeHashCommands();
        commands.values.put(
            ValkeyAuthoritySnapshotCacheStore.cacheKey(AuthoritySnapshotInvalidation.PLAYER_RANK, scope),
            Map.of(
                "projectionFamily", "player_rank",
                "aggregateScope", scope,
                "revision", "4",
                "statePayload", "{"
            )
        );
        ValkeyAuthoritySnapshotCacheStore store = new ValkeyAuthoritySnapshotCacheStore(commands, Duration.ofSeconds(2));

        assertThat(store.readRank(scope)).isEmpty();
    }

    @Test
    void lettuceScriptsPreserveRevisionAndTtlSemantics() {
        assertThat(ValkeyAuthoritySnapshotCacheStore.writeIfNewerScript())
            .contains("redis.call('HGET', KEYS[1], 'revision')")
            .contains("redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])")
            .contains("redis.call('PEXPIRE', KEYS[1], ttl)");
        assertThat(ValkeyAuthoritySnapshotCacheStore.invalidateAtOrBelowScript())
            .contains("tonumber(existing) <= tonumber(ARGV[1])")
            .contains("redis.call('DEL', KEYS[1])");
    }

    @Tag("live-substrate")
    @Test
    void liveValkeyPreservesSnapshotRevisionTtlAndInvalidationSemantics() {
        RedisClient client = null;
        StatefulRedisConnection<String, String> connection = null;
        try (GenericContainer<?> valkey = startValkey()) {
            client = RedisClient.create(RedisURI.builder()
                .withHost(valkey.getHost())
                .withPort(valkey.getMappedPort(6379))
                .withTimeout(Duration.ofSeconds(5))
                .build());
            connection = client.connect();
            ValkeyAuthoritySnapshotCacheStore store = new ValkeyAuthoritySnapshotCacheStore(
                connection.sync(),
                Duration.ofSeconds(30)
            );
            UUID playerId = UUID.randomUUID();
            String scope = "rank:player:" + playerId;
            DataAuthority.PlayerRankSnapshot older = rankSnapshot(playerId, "VIP", 4L, 1_000L);
            DataAuthority.PlayerRankSnapshot newer = rankSnapshot(playerId, "ADMIN", 5L, 1_001L);

            store.writeRank(line(scope, newer, 1_250L));
            store.writeRank(line(scope, older, 1_300L));

            assertThat(store.readRank(scope))
                .get()
                .extracting(AuthoritySnapshotCacheStore.SnapshotLine::snapshot)
                .isEqualTo(newer);
            assertThat(connection.sync().pttl(ValkeyAuthoritySnapshotCacheStore.cacheKey(
                AuthoritySnapshotInvalidation.PLAYER_RANK,
                scope
            ))).isPositive();

            store.invalidateRank(scope, 4L, 1_301L);
            assertThat(store.readRank(scope)).isPresent();
            store.invalidateRank(scope, 5L, 1_302L);
            assertThat(store.readRank(scope)).isEmpty();
        } finally {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            if (client != null) {
                client.shutdown();
            }
        }
    }

    private static GenericContainer<?> startValkey() {
        GenericContainer<?> container = new GenericContainer<>(VALKEY_IMAGE).withExposedPorts(6379);
        try {
            container.start();
            return container;
        } catch (RuntimeException exception) {
            unavailableLiveSubstrate("Valkey", exception);
            throw exception;
        }
    }

    private static void unavailableLiveSubstrate(String substrate, RuntimeException exception) {
        String message = "Live " + substrate + " proof requires Docker/Testcontainers; startup failed: "
            + exception.getMessage();
        if (liveSubstratesRequired()) {
            throw new IllegalStateException(message, exception);
        }
        Assumptions.assumeTrue(false, message);
    }

    private static boolean liveSubstratesRequired() {
        return Boolean.getBoolean("fulcrum.test.substrates.requireLive")
            || Boolean.parseBoolean(System.getenv().getOrDefault(
                "FULCRUM_TEST_SUBSTRATES_REQUIRE_LIVE",
                "false"
            ));
    }

    private static AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerRankSnapshot> line(
        String scope,
        DataAuthority.PlayerRankSnapshot snapshot,
        long cachedAtEpochMillis
    ) {
        return AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            scope,
            snapshot.revision(),
            snapshot.watermark(),
            snapshot,
            cachedAtEpochMillis,
            DataAuthority.ReadProvenance.hotState()
        );
    }

    private static DataAuthority.PlayerRankSnapshot rankSnapshot(
        UUID playerId,
        String primaryRank,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return new DataAuthority.PlayerRankSnapshot(
            playerId,
            primaryRank,
            List.of("DEFAULT", primaryRank),
            revision,
            new DataAuthority.SnapshotWatermark(
                AuthoritySnapshotCacheCodec.PROJECTION_NAME,
                "rank:player:" + playerId,
                AuthoritySnapshotInvalidation.PLAYER_RANK,
                playerId.toString(),
                "rank",
                "state.rank",
                "rank:player:" + playerId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                revision,
                eventCreatedEpochMillis,
                0,
                revision,
                "state-fingerprint-" + revision,
                "event-chain-hash-" + revision
            )
        );
    }

    private static DataAuthority.PlayerPresenceSnapshot presenceSnapshot(
        UUID subjectId,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return new DataAuthority.PlayerPresenceSnapshot(
            subjectId,
            subjectId,
            "Richa",
            true,
            "lobby",
            "proxy-a",
            UUID.randomUUID(),
            eventCreatedEpochMillis,
            revision,
            new DataAuthority.SnapshotWatermark(
                AuthoritySnapshotCacheCodec.PROJECTION_NAME,
                DataAuthority.Subject.SCOPE_PREFIX + subjectId,
                AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
                subjectId.toString(),
                "session",
                "state.session",
                DataAuthority.Subject.SCOPE_PREFIX + subjectId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                revision,
                eventCreatedEpochMillis,
                0,
                revision,
                "state-fingerprint-" + revision,
                "event-chain-hash-" + revision
            )
        );
    }

    private static final class FakeHashCommands implements ValkeyAuthoritySnapshotCacheStore.HashCommands {
        private final Map<String, Map<String, String>> values = new HashMap<>();
        private long ttlMillis;

        @Override
        public Map<String, String> read(String key) {
            return values.getOrDefault(key, Map.of());
        }

        @Override
        public void writeIfNewer(String key, long revision, Map<String, String> fields, long ttlMillis) {
            long existingRevision = Long.parseLong(values.getOrDefault(key, Map.of("revision", "0")).get("revision"));
            if (existingRevision <= revision) {
                values.put(key, Map.copyOf(fields));
                this.ttlMillis = ttlMillis;
            }
        }

        @Override
        public void invalidateAtOrBelow(String key, long revision) {
            long existingRevision = Long.parseLong(values.getOrDefault(key, Map.of("revision", "0")).get("revision"));
            if (existingRevision <= revision) {
                values.remove(key);
            }
        }

        private long ttlMillis() {
            return ttlMillis;
        }
    }
}
