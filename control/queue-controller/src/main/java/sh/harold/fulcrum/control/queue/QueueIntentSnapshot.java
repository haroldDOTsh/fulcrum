package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record QueueIntentSnapshot(
        QueueIntentId queueIntentId,
        List<SubjectId> subjectIds,
        ExperienceId experienceId,
        Optional<String> modeId,
        PoolId poolId,
        int priority,
        Instant createdAt,
        Instant deadlineAt,
        QueueIntentStatus status,
        Optional<RosterIntentId> rosterIntentId,
        TraceEnvelope traceEnvelope,
        Instant updatedAt) {
    public QueueIntentSnapshot {
        queueIntentId = Objects.requireNonNull(queueIntentId, "queueIntentId");
        subjectIds = List.copyOf(Objects.requireNonNull(subjectIds, "subjectIds"));
        if (subjectIds.isEmpty()) {
            throw new IllegalArgumentException("subjectIds must not be empty");
        }
        if (new HashSet<>(subjectIds).size() != subjectIds.size()) {
            throw new IllegalArgumentException("subjectIds must not contain duplicates");
        }
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        modeId = modeId == null
                ? Optional.empty()
                : modeId.map(value -> ControlQueueStrings.requireNonBlank(value, "modeId"));
        poolId = Objects.requireNonNull(poolId, "poolId");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be non-negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (!deadlineAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("deadlineAt must be after createdAt");
        }
        status = Objects.requireNonNull(status, "status");
        rosterIntentId = rosterIntentId == null ? Optional.empty() : rosterIntentId;
        if (status != QueueIntentStatus.ROSTERED && rosterIntentId.isPresent()) {
            throw new IllegalArgumentException("only rostered queue intents may carry rosterIntentId");
        }
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static QueueIntentSnapshot from(SubmitQueueIntent command) {
        return new QueueIntentSnapshot(
                command.queueIntentId(),
                command.subjectIds(),
                command.experienceId(),
                command.modeId(),
                command.poolId(),
                command.priority(),
                command.createdAt(),
                command.deadlineAt(),
                QueueIntentStatus.WAITING,
                Optional.empty(),
                command.traceEnvelope(),
                command.createdAt());
    }

    QueuePartitionKey partitionKey() {
        return new QueuePartitionKey(experienceId, modeId, poolId);
    }

    QueueIntentSnapshot cancel(Instant cancelledAt) {
        return transition(QueueIntentStatus.CANCELLED, Optional.empty(), cancelledAt);
    }

    QueueIntentSnapshot expire(Instant expiredAt) {
        return transition(QueueIntentStatus.EXPIRED, Optional.empty(), expiredAt);
    }

    QueueIntentSnapshot markRostered(RosterIntentId rosterIntentId, Instant rosteredAt) {
        return transition(QueueIntentStatus.ROSTERED, Optional.of(rosterIntentId), rosteredAt);
    }

    private QueueIntentSnapshot transition(
            QueueIntentStatus nextStatus,
            Optional<RosterIntentId> nextRosterIntentId,
            Instant updatedAt) {
        return new QueueIntentSnapshot(
                queueIntentId,
                subjectIds,
                experienceId,
                modeId,
                poolId,
                priority,
                createdAt,
                deadlineAt,
                nextStatus,
                nextRosterIntentId,
                traceEnvelope,
                updatedAt);
    }
}
