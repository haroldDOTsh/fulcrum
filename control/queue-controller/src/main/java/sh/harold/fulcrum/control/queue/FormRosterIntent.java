package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record FormRosterIntent(
        RosterIntentId rosterIntentId,
        QueuePartitionKey partitionKey,
        List<QueueIntentId> queueIntentIds,
        int maxSubjects,
        Instant formedAt,
        TraceEnvelope traceEnvelope) implements QueueRosterCommand {
    public FormRosterIntent {
        rosterIntentId = Objects.requireNonNull(rosterIntentId, "rosterIntentId");
        partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        queueIntentIds = List.copyOf(Objects.requireNonNull(queueIntentIds, "queueIntentIds"));
        if (queueIntentIds.isEmpty()) {
            throw new IllegalArgumentException("queueIntentIds must not be empty");
        }
        if (new HashSet<>(queueIntentIds).size() != queueIntentIds.size()) {
            throw new IllegalArgumentException("queueIntentIds must not contain duplicates");
        }
        if (maxSubjects <= 0) {
            throw new IllegalArgumentException("maxSubjects must be positive");
        }
        formedAt = Objects.requireNonNull(formedAt, "formedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
