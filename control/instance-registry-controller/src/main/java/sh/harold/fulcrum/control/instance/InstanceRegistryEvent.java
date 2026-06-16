package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record InstanceRegistryEvent(
        String eventType,
        InstanceSnapshot snapshot,
        Revision revision,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public InstanceRegistryEvent {
        eventType = ControlInstanceStrings.requireNonBlank(eventType, "eventType");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static InstanceRegistryEvent from(
            InstanceRegistryControlCommand<? extends InstanceRegistryCommand> command,
            Revision revision,
            InstanceSnapshot snapshot) {
        return new InstanceRegistryEvent(
                "ctrl.instance." + snapshot.status().name().toLowerCase(),
                snapshot,
                revision,
                command.envelope().traceEnvelope(),
                command.receivedAt());
    }

    public String eventKey() {
        return "ctrl.evt.instance:" + snapshot.instanceId().value() + ":" + revision.value();
    }

    public String wireValue() {
        return eventType
                + "|instanceId=" + snapshot.instanceId().value()
                + "|status=" + snapshot.status().name()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
