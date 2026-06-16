package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RosterIntentSnapshot(
        RosterIntentId rosterIntentId,
        List<QueueIntentId> queueIntentIds,
        List<SubjectId> subjectIds,
        ExperienceId experienceId,
        Optional<String> modeId,
        PoolId poolId,
        int maxSubjects,
        RosterIntentStatus status,
        TraceEnvelope traceEnvelope,
        Instant formedAt) {
    static final Comparator<QueueIntentSnapshot> QUEUE_ORDER = Comparator
            .comparingInt(QueueIntentSnapshot::priority)
            .reversed()
            .thenComparing(QueueIntentSnapshot::createdAt)
            .thenComparing(snapshot -> snapshot.queueIntentId().value());

    public RosterIntentSnapshot {
        rosterIntentId = Objects.requireNonNull(rosterIntentId, "rosterIntentId");
        queueIntentIds = List.copyOf(Objects.requireNonNull(queueIntentIds, "queueIntentIds"));
        if (queueIntentIds.isEmpty()) {
            throw new IllegalArgumentException("queueIntentIds must not be empty");
        }
        if (new HashSet<>(queueIntentIds).size() != queueIntentIds.size()) {
            throw new IllegalArgumentException("queueIntentIds must not contain duplicates");
        }
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
        if (maxSubjects <= 0) {
            throw new IllegalArgumentException("maxSubjects must be positive");
        }
        if (subjectIds.size() > maxSubjects) {
            throw new IllegalArgumentException("subjectIds exceeds maxSubjects");
        }
        status = Objects.requireNonNull(status, "status");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        formedAt = Objects.requireNonNull(formedAt, "formedAt");
    }

    public static RosterIntentSnapshot from(FormRosterIntent command, List<QueueIntentSnapshot> selectedQueueIntents) {
        List<QueueIntentSnapshot> ordered = selectedQueueIntents.stream()
                .sorted(QUEUE_ORDER)
                .toList();
        List<SubjectId> orderedSubjects = ordered.stream()
                .flatMap(snapshot -> snapshot.subjectIds().stream())
                .toList();
        return new RosterIntentSnapshot(
                command.rosterIntentId(),
                ordered.stream().map(QueueIntentSnapshot::queueIntentId).toList(),
                orderedSubjects,
                command.partitionKey().experienceId(),
                command.partitionKey().modeId(),
                command.partitionKey().poolId(),
                command.maxSubjects(),
                RosterIntentStatus.FORMED,
                command.traceEnvelope(),
                command.formedAt());
    }

    public String wireValue(Revision revision) {
        return "rosterIntentId=" + rosterIntentId.value()
                + "|queueIntentCount=" + queueIntentIds.size()
                + "|subjectCount=" + subjectIds.size()
                + "|experienceId=" + experienceId.value()
                + "|poolId=" + poolId.value()
                + "|status=" + status.name()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
