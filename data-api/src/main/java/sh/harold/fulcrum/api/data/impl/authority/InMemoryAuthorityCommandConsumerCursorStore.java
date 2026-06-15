package sh.harold.fulcrum.api.data.impl.authority;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory command consumer cursor store for compatibility tests.
 */
public final class InMemoryAuthorityCommandConsumerCursorStore
    implements AuthorityCommandConsumerCursorStore {
    private final ConcurrentMap<CursorKey, Cursor> cursors = new ConcurrentHashMap<>();

    @Override
    public Optional<Cursor> cursor(String commandDomain, int partition) {
        return Optional.ofNullable(cursors.get(new CursorKey(commandDomain, partition)));
    }

    @Override
    public void recordApplied(AuthorityLogCommandWorker.PartitionResult result) {
        AuthorityCommandConsumerCursorStore.cursorFor(result).ifPresent(cursor ->
            cursors.merge(
                new CursorKey(cursor.commandDomain(), cursor.partition()),
                cursor,
                (previous, candidate) -> candidate.committedOffset() > previous.committedOffset()
                    ? candidate
                    : previous
            ));
    }

    private record CursorKey(String commandDomain, int partition) {
        private CursorKey {
            if (commandDomain == null || commandDomain.isBlank()) {
                throw new IllegalArgumentException("commandDomain is required");
            }
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            commandDomain = Objects.requireNonNull(commandDomain, "commandDomain");
        }
    }
}
