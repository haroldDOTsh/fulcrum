package sh.harold.fulcrum.api.data.impl.authority;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory projection cursor store for tests and local bootstrap loops.
 */
public final class InMemoryAuthorityStateProjectionCursorStore
    implements AuthorityStateProjectionCursorStore {
    private final ConcurrentMap<CursorKey, Cursor> cursors = new ConcurrentHashMap<>();

    @Override
    public Optional<Cursor> cursor(
        String projectionName,
        String projectionVersion,
        String commandDomain,
        int partition
    ) {
        return Optional.ofNullable(cursors.get(new CursorKey(
            projectionName,
            projectionVersion,
            commandDomain,
            partition
        )));
    }

    @Override
    public void recordApplied(AuthorityStateProjectionWorker.PartitionResult result) {
        AuthorityStateProjectionCursorStore.cursorFor(result).ifPresent(cursor ->
            cursors.merge(
                new CursorKey(
                    cursor.projectionName(),
                    cursor.projectionVersion(),
                    cursor.commandDomain(),
                    cursor.partition()
                ),
                cursor,
                (previous, candidate) -> candidate.committedOffset() > previous.committedOffset()
                    ? candidate
                    : previous
            ));
    }

    private record CursorKey(String projectionName, String projectionVersion, String commandDomain, int partition) {
        private CursorKey {
            projectionName = requireText(projectionName, "projectionName");
            projectionVersion = requireText(projectionVersion, "projectionVersion");
            commandDomain = requireText(commandDomain, "commandDomain");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return Objects.requireNonNull(value, field).trim();
        }
    }
}
