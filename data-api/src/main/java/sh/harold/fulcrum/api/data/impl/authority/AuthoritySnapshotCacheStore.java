package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.Optional;

/**
 * Cache-line port for authority-owned hot snapshots.
 *
 * <p>The data-authority read path treats this as the cache tier in front of the
 * hot-state projection. Local maps and Valkey can both implement this contract
 * without changing callers.</p>
 */
public interface AuthoritySnapshotCacheStore {
    int SNAPSHOT_LINE_SCHEMA_VERSION = 1;

    Optional<SnapshotLine<DataAuthority.PlayerProfileSnapshot>> readProfile(String aggregateScope);

    Optional<SnapshotLine<DataAuthority.PlayerRankSnapshot>> readRank(String aggregateScope);

    void writeProfile(SnapshotLine<DataAuthority.PlayerProfileSnapshot> line);

    void writeRank(SnapshotLine<DataAuthority.PlayerRankSnapshot> line);

    void invalidateProfile(String aggregateScope, long revision, long invalidatedAtEpochMillis);

    void invalidateRank(String aggregateScope, long revision, long invalidatedAtEpochMillis);

    record SnapshotLine<T>(
        int schemaVersion,
        String projectionFamily,
        String aggregateScope,
        long revision,
        DataAuthority.SnapshotWatermark watermark,
        T snapshot,
        long cachedAtEpochMillis,
        DataAuthority.ReadProvenance provenance,
        boolean tombstone
    ) {
        public SnapshotLine {
            projectionFamily = normalize(projectionFamily);
            aggregateScope = normalize(aggregateScope);
            revision = Math.max(0L, revision);
            cachedAtEpochMillis = Math.max(0L, cachedAtEpochMillis);
            provenance = provenance == null ? DataAuthority.ReadProvenance.unknown() : provenance;
            if (!tombstone) {
                Objects.requireNonNull(snapshot, "snapshot");
            }
        }

        public static <T> SnapshotLine<T> snapshot(
            String projectionFamily,
            String aggregateScope,
            long revision,
            DataAuthority.SnapshotWatermark watermark,
            T snapshot,
            long cachedAtEpochMillis,
            DataAuthority.ReadProvenance provenance
        ) {
            return new SnapshotLine<>(
                SNAPSHOT_LINE_SCHEMA_VERSION,
                projectionFamily,
                aggregateScope,
                revision,
                watermark,
                snapshot,
                cachedAtEpochMillis,
                provenance,
                false
            );
        }

        public static <T> SnapshotLine<T> tombstone(
            String projectionFamily,
            String aggregateScope,
            long revision,
            long invalidatedAtEpochMillis,
            DataAuthority.ReadProvenance provenance
        ) {
            return new SnapshotLine<>(
                SNAPSHOT_LINE_SCHEMA_VERSION,
                projectionFamily,
                aggregateScope,
                revision,
                null,
                null,
                invalidatedAtEpochMillis,
                provenance,
                true
            );
        }

        public boolean serveable() {
            return schemaVersion == SNAPSHOT_LINE_SCHEMA_VERSION
                && !tombstone
                && snapshot != null
                && revision > 0L
                && !projectionFamily.isBlank()
                && !aggregateScope.isBlank();
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? "" : value;
        }
    }
}
