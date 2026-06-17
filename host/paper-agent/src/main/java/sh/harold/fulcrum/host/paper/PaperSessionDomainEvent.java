package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.core.session.SessionDomainEvent;

import java.time.Instant;
import java.util.Objects;

record PaperSessionDomainEvent(
        String eventType,
        SessionId sessionId,
        RouteId routeId,
        SubjectId subjectId,
        TraceEnvelope traceEnvelope,
        Instant occurredAt) implements SessionDomainEvent {
    PaperSessionDomainEvent {
        eventType = PaperArtifactNames.requireNonBlank(eventType, "eventType");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
