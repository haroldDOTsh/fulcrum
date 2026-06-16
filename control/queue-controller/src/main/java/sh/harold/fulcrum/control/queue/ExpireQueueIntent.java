package sh.harold.fulcrum.control.queue;

import java.time.Instant;
import java.util.Objects;

public record ExpireQueueIntent(
        QueuePartitionKey partitionKey,
        QueueIntentId queueIntentId,
        Instant expiredAt) implements QueueRosterCommand {
    public ExpireQueueIntent {
        partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        queueIntentId = Objects.requireNonNull(queueIntentId, "queueIntentId");
        expiredAt = Objects.requireNonNull(expiredAt, "expiredAt");
    }
}
