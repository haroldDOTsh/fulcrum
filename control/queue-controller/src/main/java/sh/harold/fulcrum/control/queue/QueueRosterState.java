package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record QueueRosterState(
        Map<QueueIntentId, QueueIntentSnapshot> queueIntents,
        Map<RosterIntentId, RosterIntentSnapshot> rosterIntents) {
    public QueueRosterState {
        queueIntents = Map.copyOf(Objects.requireNonNull(queueIntents, "queueIntents"));
        rosterIntents = Map.copyOf(Objects.requireNonNull(rosterIntents, "rosterIntents"));
    }

    public static QueueRosterState empty() {
        return new QueueRosterState(Map.of(), Map.of());
    }

    public Optional<QueueIntentSnapshot> queueIntent(QueueIntentId queueIntentId) {
        return Optional.ofNullable(queueIntents.get(Objects.requireNonNull(queueIntentId, "queueIntentId")));
    }

    public Optional<RosterIntentSnapshot> rosterIntent(RosterIntentId rosterIntentId) {
        return Optional.ofNullable(rosterIntents.get(Objects.requireNonNull(rosterIntentId, "rosterIntentId")));
    }

    QueueRosterState withQueueIntent(QueueIntentSnapshot snapshot) {
        Map<QueueIntentId, QueueIntentSnapshot> next = new java.util.HashMap<>(queueIntents);
        next.put(snapshot.queueIntentId(), snapshot);
        return new QueueRosterState(next, rosterIntents);
    }

    QueueRosterState withRosterIntent(RosterIntentSnapshot snapshot) {
        Map<RosterIntentId, RosterIntentSnapshot> next = new java.util.HashMap<>(rosterIntents);
        next.put(snapshot.rosterIntentId(), snapshot);
        return new QueueRosterState(queueIntents, next);
    }

    List<QueueIntentSnapshot> selectedQueueIntents(FormRosterIntent command) {
        return command.queueIntentIds().stream()
                .map(queueIntentId -> queueIntent(queueIntentId).orElseThrow())
                .sorted(RosterIntentSnapshot.QUEUE_ORDER)
                .toList();
    }

    boolean hasWaitingSubjectOverlap(List<SubjectId> subjectIds) {
        Set<SubjectId> requested = new HashSet<>(subjectIds);
        return queueIntents.values().stream()
                .filter(snapshot -> snapshot.status() == QueueIntentStatus.WAITING)
                .flatMap(snapshot -> snapshot.subjectIds().stream())
                .anyMatch(requested::contains);
    }

    String wireValue(QueuePartitionKey partitionKey, Revision revision, TraceEnvelope traceEnvelope) {
        return "partition=" + partitionKey.canonicalValue()
                + "|queueIntentCount=" + queueIntents.size()
                + "|rosterIntentCount=" + rosterIntents.size()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
