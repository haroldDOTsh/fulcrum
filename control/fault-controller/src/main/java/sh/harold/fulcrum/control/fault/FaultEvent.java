package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record FaultEvent(
        String eventType,
        FaultRecord faultRecord,
        Revision revision,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public FaultEvent {
        eventType = ControlFaultStrings.requireNonBlank(eventType, "eventType");
        faultRecord = Objects.requireNonNull(faultRecord, "faultRecord");
        revision = Objects.requireNonNull(revision, "revision");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static FaultEvent from(FaultControlCommand<? extends FaultCommand> command, Revision revision, FaultRecord faultRecord) {
        return new FaultEvent(
                "ctrl.fault." + faultRecord.quarantineState().name().toLowerCase(),
                faultRecord,
                revision,
                command.envelope().traceEnvelope(),
                command.receivedAt());
    }

    public String eventKey() {
        return "ctrl.evt.fault:" + faultRecord.faultId().value() + ":" + revision.value();
    }

    public String wireValue() {
        return eventType
                + "|faultId=" + faultRecord.faultId().value()
                + "|quarantineState=" + faultRecord.quarantineState().name()
                + "|count=" + faultRecord.count()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
