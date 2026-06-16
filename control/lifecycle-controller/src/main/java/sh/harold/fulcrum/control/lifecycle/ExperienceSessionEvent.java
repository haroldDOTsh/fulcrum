package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;

import java.time.Instant;
import java.util.Objects;

public record ExperienceSessionEvent(
        String eventType,
        ExperienceSessionRecord sessionRecord,
        Revision revision,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    public ExperienceSessionEvent {
        eventType = ControlLifecycleStrings.requireNonBlank(eventType, "eventType");
        sessionRecord = Objects.requireNonNull(sessionRecord, "sessionRecord");
        revision = Objects.requireNonNull(revision, "revision");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static ExperienceSessionEvent from(
            ExperienceSessionControlCommand<? extends ExperienceSessionCommand> command,
            Revision revision,
            ExperienceSessionRecord sessionRecord) {
        return new ExperienceSessionEvent(
                "ctrl.experience-session." + sessionRecord.status().name().toLowerCase(),
                sessionRecord,
                revision,
                command.envelope().traceEnvelope(),
                command.receivedAt());
    }

    public String eventKey() {
        return "ctrl.evt.experience-session:" + sessionRecord.sessionId().value() + ":" + revision.value();
    }

    public String wireValue() {
        return eventType
                + "|sessionId=" + sessionRecord.sessionId().value()
                + "|status=" + sessionRecord.status().name()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
