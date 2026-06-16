package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record LifecycleTraceEvent(
        String eventType,
        LifecycleTraceRecord traceRecord,
        Revision revision,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public LifecycleTraceEvent {
        eventType = ControlLifecycleStrings.requireNonBlank(eventType, "eventType");
        traceRecord = Objects.requireNonNull(traceRecord, "traceRecord");
        revision = Objects.requireNonNull(revision, "revision");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static LifecycleTraceEvent from(
            LifecycleTraceControlCommand<? extends LifecycleTraceCommand> command,
            Revision revision,
            LifecycleTraceRecord traceRecord) {
        return new LifecycleTraceEvent(
                "ctrl.lifecycle." + command.envelope().payload().getClass().getSimpleName(),
                traceRecord,
                revision,
                command.envelope().traceEnvelope(),
                command.receivedAt());
    }

    public String eventKey() {
        return "ctrl.evt.lifecycle-trace:" + traceRecord.traceId().value() + ":" + revision.value();
    }

    public String wireValue() {
        return eventType
                + "|traceId=" + traceRecord.traceId().value()
                + "|entryCount=" + traceRecord.entries().size()
                + "|revision=" + revision.value()
                + "|spanId=" + traceEnvelope.spanId();
    }
}
