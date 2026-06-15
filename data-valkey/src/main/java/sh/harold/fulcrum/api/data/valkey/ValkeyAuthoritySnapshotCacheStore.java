package sh.harold.fulcrum.api.data.valkey;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotCacheCodec;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotCacheStore;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotInvalidation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Valkey-backed hot snapshot cache store.
 *
 * <p>The caller owns the Lettuce connection lifecycle; this adapter only owns key
 * naming, cache-line serialization, and compare-by-revision mutation semantics.</p>
 */
public final class ValkeyAuthoritySnapshotCacheStore implements AuthoritySnapshotCacheStore {
    public static final String STORE_NAME = AuthoritySnapshotCacheCodec.STORE_NAME;
    public static final String WIRE_PROTOCOL = AuthoritySnapshotCacheCodec.WIRE_PROTOCOL;

    private static final String WRITE_IF_NEWER_SCRIPT = """
        local existing = redis.call('HGET', KEYS[1], 'revision')
        if existing and tonumber(existing) > tonumber(ARGV[1]) then
            return 0
        end
        for i = 2, #ARGV - 1, 2 do
            redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
        end
        local ttl = tonumber(ARGV[#ARGV])
        if ttl and ttl > 0 then
            redis.call('PEXPIRE', KEYS[1], ttl)
        end
        return 1
        """;
    private static final String INVALIDATE_AT_OR_BELOW_SCRIPT = """
        local existing = redis.call('HGET', KEYS[1], 'revision')
        if not existing then
            return 0
        end
        if tonumber(existing) <= tonumber(ARGV[1]) then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """;

    private final HashCommands commands;
    private final long ttlMillis;

    public ValkeyAuthoritySnapshotCacheStore(RedisCommands<String, String> commands, Duration ttl) {
        this(new LettuceHashCommands(commands), ttl);
    }

    ValkeyAuthoritySnapshotCacheStore(HashCommands commands, Duration ttl) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.ttlMillis = ttl == null ? Duration.ofMinutes(5).toMillis() : Math.max(0L, ttl.toMillis());
    }

    @Override
    public Optional<SnapshotLine<DataAuthority.PlayerProfileSnapshot>> readProfile(String aggregateScope) {
        return read(
            AuthoritySnapshotInvalidation.PLAYER_PROFILE,
            aggregateScope,
            AuthoritySnapshotCacheCodec::profileSnapshot
        );
    }

    @Override
    public Optional<SnapshotLine<DataAuthority.PlayerRankSnapshot>> readRank(String aggregateScope) {
        return read(
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            aggregateScope,
            AuthoritySnapshotCacheCodec::rankSnapshot
        );
    }

    @Override
    public void writeProfile(SnapshotLine<DataAuthority.PlayerProfileSnapshot> line) {
        write(line, AuthoritySnapshotCacheCodec.profileFields(line));
    }

    @Override
    public void writeRank(SnapshotLine<DataAuthority.PlayerRankSnapshot> line) {
        write(line, AuthoritySnapshotCacheCodec.rankFields(line));
    }

    @Override
    public void invalidateProfile(String aggregateScope, long revision, long invalidatedAtEpochMillis) {
        commands.invalidateAtOrBelow(cacheKey(AuthoritySnapshotInvalidation.PLAYER_PROFILE, aggregateScope), revision);
    }

    @Override
    public void invalidateRank(String aggregateScope, long revision, long invalidatedAtEpochMillis) {
        commands.invalidateAtOrBelow(cacheKey(AuthoritySnapshotInvalidation.PLAYER_RANK, aggregateScope), revision);
    }

    public static String cacheKey(String projectionFamily, String aggregateScope) {
        return AuthoritySnapshotCacheCodec.cacheKey(projectionFamily, aggregateScope);
    }

    static String writeIfNewerScript() {
        return WRITE_IF_NEWER_SCRIPT;
    }

    static String invalidateAtOrBelowScript() {
        return INVALIDATE_AT_OR_BELOW_SCRIPT;
    }

    private <T> Optional<SnapshotLine<T>> read(
        String projectionFamily,
        String aggregateScope,
        Decoder<T> decoder
    ) {
        try {
            Map<String, String> fields = commands.read(cacheKey(projectionFamily, aggregateScope));
            if (fields == null || fields.isEmpty()) {
                return Optional.empty();
            }
            return decoder.decode(fields).map(snapshot -> SnapshotLine.snapshot(
                projectionFamily,
                aggregateScope,
                revision(snapshot),
                watermark(snapshot),
                snapshot,
                AuthoritySnapshotCacheCodec.cachedAtEpochMillis(fields),
                DataAuthority.ReadProvenance.cache(
                    AuthoritySnapshotCacheCodec.cachedAtEpochMillis(fields),
                    AuthoritySnapshotCacheCodec.cachedAtEpochMillis(fields),
                    ttlMillis
                )
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void write(SnapshotLine<?> line, Map<String, String> fields) {
        commands.writeIfNewer(cacheKey(line.projectionFamily(), line.aggregateScope()), line.revision(), fields, ttlMillis);
    }

    private static long revision(Object snapshot) {
        if (snapshot instanceof DataAuthority.PlayerProfileSnapshot profile) {
            return profile.revision();
        }
        if (snapshot instanceof DataAuthority.PlayerRankSnapshot rank) {
            return rank.revision();
        }
        return 0L;
    }

    private static DataAuthority.SnapshotWatermark watermark(Object snapshot) {
        if (snapshot instanceof DataAuthority.PlayerProfileSnapshot profile) {
            return profile.watermark();
        }
        if (snapshot instanceof DataAuthority.PlayerRankSnapshot rank) {
            return rank.watermark();
        }
        return null;
    }

    @FunctionalInterface
    interface Decoder<T> {
        Optional<T> decode(Map<String, String> fields);
    }

    interface HashCommands {
        Map<String, String> read(String key);

        void writeIfNewer(String key, long revision, Map<String, String> fields, long ttlMillis);

        void invalidateAtOrBelow(String key, long revision);
    }

    private static final class LettuceHashCommands implements HashCommands {
        private final RedisCommands<String, String> commands;

        private LettuceHashCommands(RedisCommands<String, String> commands) {
            this.commands = Objects.requireNonNull(commands, "commands");
        }

        @Override
        public Map<String, String> read(String key) {
            return commands.hgetall(key);
        }

        @Override
        public void writeIfNewer(String key, long revision, Map<String, String> fields, long ttlMillis) {
            List<String> arguments = new ArrayList<>();
            arguments.add(Long.toString(Math.max(0L, revision)));
            fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    arguments.add(entry.getKey());
                    arguments.add(entry.getValue());
                });
            arguments.add(Long.toString(Math.max(0L, ttlMillis)));
            commands.eval(
                WRITE_IF_NEWER_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] { key },
                arguments.toArray(String[]::new)
            );
        }

        @Override
        public void invalidateAtOrBelow(String key, long revision) {
            commands.eval(
                INVALIDATE_AT_OR_BELOW_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] { key },
                Long.toString(Math.max(0L, revision))
            );
        }
    }
}
