package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record QueueRosterEvent(
        String eventType,
        QueuePartitionKey partitionKey,
        QueueRosterState state,
        Revision revision,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public QueueRosterEvent {
        eventType = ControlQueueStrings.requireNonBlank(eventType, "eventType");
        partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        state = Objects.requireNonNull(state, "state");
        revision = Objects.requireNonNull(revision, "revision");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static QueueRosterEvent from(
            QueueRosterControlCommand<? extends QueueRosterCommand> command,
            Revision revision,
            QueueRosterState state) {
        return new QueueRosterEvent(
                eventType(command.envelope().payload()),
                command.envelope().payload().partitionKey(),
                state,
                revision,
                command.envelope().traceEnvelope(),
                command.receivedAt());
    }

    public String eventKey() {
        return "ctrl.evt.queue-roster:" + partitionKey.canonicalValue() + ":" + revision.value();
    }

    public String wireValue() {
        return eventType
                + "|partition=" + partitionKey.canonicalValue()
                + "|queueIntentCount=" + state.queueIntents().size()
                + "|rosterIntentCount=" + state.rosterIntents().size()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }

    private static String eventType(QueueRosterCommand command) {
        if (command instanceof SubmitQueueIntent) {
            return "ctrl.queue-intent.submitted";
        }
        if (command instanceof CancelQueueIntent) {
            return "ctrl.queue-intent.cancelled";
        }
        if (command instanceof ExpireQueueIntent) {
            return "ctrl.queue-intent.expired";
        }
        if (command instanceof FormRosterIntent) {
            return "ctrl.roster-intent.formed";
        }
        return "ctrl.queue-roster.unknown";
    }
}
