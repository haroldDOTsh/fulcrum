package sh.harold.fulcrum.control.queue;

import java.time.Instant;
import java.util.Objects;

public record CancelQueueIntent(
        QueuePartitionKey partitionKey,
        QueueIntentId queueIntentId,
        Instant cancelledAt) implements QueueRosterCommand {
    public CancelQueueIntent {
        partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        queueIntentId = Objects.requireNonNull(queueIntentId, "queueIntentId");
        cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt");
    }
}
