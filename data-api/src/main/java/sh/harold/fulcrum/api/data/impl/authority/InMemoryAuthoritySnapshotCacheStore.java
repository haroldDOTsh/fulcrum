package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Node-local implementation of the hot snapshot cache port.
 */
public final class InMemoryAuthoritySnapshotCacheStore implements AuthoritySnapshotCacheStore {
    private final ConcurrentMap<String, SnapshotLine<DataAuthority.PlayerProfileSnapshot>> profileSnapshots =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SnapshotLine<DataAuthority.PlayerRankSnapshot>> rankSnapshots =
        new ConcurrentHashMap<>();

    @Override
    public Optional<SnapshotLine<DataAuthority.PlayerProfileSnapshot>> readProfile(String aggregateScope) {
        return Optional.ofNullable(profileSnapshots.get(requireScope(aggregateScope)));
    }

    @Override
    public Optional<SnapshotLine<DataAuthority.PlayerRankSnapshot>> readRank(String aggregateScope) {
        return Optional.ofNullable(rankSnapshots.get(requireScope(aggregateScope)));
    }

    @Override
    public void writeProfile(SnapshotLine<DataAuthority.PlayerProfileSnapshot> line) {
        putIfNewer(profileSnapshots, requireServeable(line));
    }

    @Override
    public void writeRank(SnapshotLine<DataAuthority.PlayerRankSnapshot> line) {
        putIfNewer(rankSnapshots, requireServeable(line));
    }

    @Override
    public void invalidateProfile(String aggregateScope, long revision, long invalidatedAtEpochMillis) {
        evictAtOrBelow(profileSnapshots, requireScope(aggregateScope), revision);
    }

    @Override
    public void invalidateRank(String aggregateScope, long revision, long invalidatedAtEpochMillis) {
        evictAtOrBelow(rankSnapshots, requireScope(aggregateScope), revision);
    }

    private static <T> void putIfNewer(ConcurrentMap<String, SnapshotLine<T>> cache, SnapshotLine<T> line) {
        cache.merge(line.aggregateScope(), line, (existing, candidate) ->
            candidate.revision() >= existing.revision() ? candidate : existing
        );
    }

    private static <T> void evictAtOrBelow(
        ConcurrentMap<String, SnapshotLine<T>> cache,
        String aggregateScope,
        long revision
    ) {
        cache.computeIfPresent(aggregateScope, (ignored, line) -> line.revision() <= revision ? null : line);
    }

    private static <T> SnapshotLine<T> requireServeable(SnapshotLine<T> line) {
        Objects.requireNonNull(line, "line");
        if (!line.serveable()) {
            throw new IllegalArgumentException("snapshot cache line is not serveable");
        }
        return line;
    }

    private static String requireScope(String aggregateScope) {
        if (aggregateScope == null || aggregateScope.isBlank()) {
            throw new IllegalArgumentException("aggregateScope is required");
        }
        return aggregateScope;
    }
}
