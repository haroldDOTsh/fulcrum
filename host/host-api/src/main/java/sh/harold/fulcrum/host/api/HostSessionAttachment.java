package sh.harold.fulcrum.host.api;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record HostSessionAttachment(
        HostInstanceIdentity instanceIdentity,
        RouteId routeId,
        SubjectId subjectId,
        SessionId sessionId,
        TraceEnvelope traceEnvelope,
        Instant attachedAt) {
    public HostSessionAttachment {
        instanceIdentity = Objects.requireNonNull(instanceIdentity, "instanceIdentity");
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        attachedAt = Objects.requireNonNull(attachedAt, "attachedAt");
    }
}
