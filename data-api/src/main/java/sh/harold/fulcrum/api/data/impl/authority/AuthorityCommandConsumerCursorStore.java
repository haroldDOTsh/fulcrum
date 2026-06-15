package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Durable resume cursor for authority command consumers.
 */
public interface AuthorityCommandConsumerCursorStore {
    static AuthorityCommandConsumerCursorStore inMemory() {
        return new InMemoryAuthorityCommandConsumerCursorStore();
    }

    Optional<Cursor> cursor(String commandDomain, int partition);

    void recordApplied(AuthorityLogCommandWorker.PartitionResult result);

    default long committedOffset(String commandDomain, int partition) {
        return cursor(commandDomain, partition)
            .map(Cursor::committedOffset)
            .orElse(-1L);
    }

    default Map<Integer, Long> committedOffsets(String commandDomain, Collection<Integer> partitions) {
        Objects.requireNonNull(partitions, "partitions");
        return partitions.stream()
            .distinct()
            .sorted()
            .collect(Collectors.toUnmodifiableMap(
                partition -> partition,
                partition -> committedOffset(commandDomain, partition)
            ));
    }

    static Optional<Cursor> cursorFor(AuthorityLogCommandWorker.PartitionResult result) {
        Objects.requireNonNull(result, "result");
        if (result.processedCount() == 0 || result.lastProcessedOffset() < 0L) {
            return Optional.empty();
        }
        AuthorityLogCommandProcessor.ProcessingResult last = result.processed().stream()
            .max(Comparator.comparingLong(processed -> processed.commandRecord().offset()))
            .orElseThrow(() -> new IllegalArgumentException("processed result is required"));
        AuthorityWriterClaim claim = result.writerClaim();
        DataAuthority.CommandResult commandResult = last.commandResult();
        return Optional.of(new Cursor(
            result.domain(),
            result.commandTopic(),
            result.partition(),
            result.lastProcessedOffset(),
            last.commandRecord().key(),
            commandResult.commandId(),
            commandResult.revision(),
            commandResult.accepted(),
            commandResult.rejectionReason().name(),
            claim.claimId(),
            claim.epoch(),
            claim.claimFingerprint(),
            claim.ownerNode(),
            System.currentTimeMillis()
        ));
    }

    record Cursor(
        String commandDomain,
        String commandTopic,
        int partition,
        long committedOffset,
        String partitionKey,
        UUID lastCommandId,
        long lastResultRevision,
        boolean lastResultAccepted,
        String lastRejectionReason,
        UUID writerClaimId,
        long writerClaimEpoch,
        String writerClaimFingerprint,
        String ownerNode,
        long updatedAtEpochMillis
    ) {
        public Cursor {
            commandDomain = requireText(commandDomain, "commandDomain");
            commandTopic = requireText(commandTopic, "commandTopic");
            if (partition < 0) {
                throw new IllegalArgumentException("partition must be non-negative");
            }
            if (committedOffset < -1L) {
                throw new IllegalArgumentException("committedOffset must be -1 or greater");
            }
            partitionKey = requireText(partitionKey, "partitionKey");
            lastCommandId = Objects.requireNonNull(lastCommandId, "lastCommandId");
            if (lastResultRevision < 0L) {
                throw new IllegalArgumentException("lastResultRevision must be non-negative");
            }
            lastRejectionReason = requireText(lastRejectionReason, "lastRejectionReason");
            writerClaimId = Objects.requireNonNull(writerClaimId, "writerClaimId");
            if (writerClaimEpoch <= 0L) {
                throw new IllegalArgumentException("writerClaimEpoch must be positive");
            }
            writerClaimFingerprint = requireText(writerClaimFingerprint, "writerClaimFingerprint");
            ownerNode = requireText(ownerNode, "ownerNode");
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
