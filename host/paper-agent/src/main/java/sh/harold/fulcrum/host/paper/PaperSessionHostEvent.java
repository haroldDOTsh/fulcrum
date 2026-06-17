package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Objects;

record PaperSessionHostEvent(
        PaperSessionHostEventType type,
        PaperJoiningSubject subject,
        RouteId routeId,
        SessionId sessionId,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) {
    PaperSessionHostEvent {
        type = Objects.requireNonNull(type, "type");
        subject = Objects.requireNonNull(subject, "subject");
        routeId = Objects.requireNonNull(routeId, "routeId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
