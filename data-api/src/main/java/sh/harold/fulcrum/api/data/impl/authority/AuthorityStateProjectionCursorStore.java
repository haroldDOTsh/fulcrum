package sh.harold.fulcrum.api.data.impl.authority;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resume cursor for consumers that rebuild projections from compacted state topics.
 */
public interface AuthorityStateProjectionCursorStore {
    static AuthorityStateProjectionCursorStore inMemory() {
        return new InMemoryAuthorityStateProjectionCursorStore();
    }

    Optional<Cursor> cursor(String projectionName, String projectionVersion, String commandDomain, int partition);

    void recordApplied(AuthorityStateProjectionWorker.PartitionResult result);

    default long committedOffset(
        String projectionName,
        String projectionVersion,
        String commandDomain,
        int partition
    ) {
        return cursor(projectionName, projectionVersion, commandDomain, partition)
            .map(Cursor::committedOffset)
            .orElse(-1L);
    }

    static Optional<Cursor> cursorFor(AuthorityStateProjectionWorker.PartitionResult result) {
        Objects.requireNonNull(result, "result");
        if (result.processedCount() == 0 || result.lastProcessedOffset() < 0L) {
            return Optional.empty();
        }
        AuthorityStateProjectionWorker.RecordResult last = result.processed().stream()
            .max(Comparator.comparingLong(processed -> processed.logRecord().offset()))
            .orElseThrow(() -> new IllegalArgumentException("processed result is required"));
        return Optional.of(new Cursor(
            result.projectionName(),
            result.projectionVersion(),
            result.domain(),
            result.stateTopic(),
            result.partition(),
            result.lastProcessedOffset(),
            last.stateRecord().partitionKey(),
            last.stateRecord().commandId(),
            last.stateRecord().eventId(),
            last.stateRecord().revision(),
            last.restoreResult().restored(),
            last.restoreResult().message(),
            System.currentTimeMillis()
        ));
    }

    record Cursor(
        String projectionName,
        String projectionVersion,
        String commandDomain,
        String stateTopic,
        int partition,
        long committedOffset,
        String partitionKey,
        UUID lastCommandId,
        UUID lastEventId,
        long lastRevision,
        boolean lastRestoreApplied,
        String lastRestoreMessage,
        long updatedAtEpochMillis
    ) {
        public Cursor {
            projectionName = requireText(projectionName, "projectionName");
            projectionVersion = requireText(projectionVersion, "projectionVersion");
            commandDomain = requireText(commandDomain, "commandDomain");
            stateTopic = requireText(stateTopic, "stateTopic");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            if (committedOffset < -1L) {
                throw new IllegalArgumentException("committedOffset must be -1 or greater");
            }
            partitionKey = requireText(partitionKey, "partitionKey");
            lastCommandId = Objects.requireNonNull(lastCommandId, "lastCommandId");
            lastEventId = Objects.requireNonNull(lastEventId, "lastEventId");
            if (lastRevision <= 0L) {
                throw new IllegalArgumentException("lastRevision must be positive");
            }
            lastRestoreMessage = requireText(lastRestoreMessage, "lastRestoreMessage");
            updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }
}
