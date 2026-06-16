package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RequestRouteAttempt(
        RouteAttemptId routeAttemptId,
        RouteId routeId,
        SessionId sessionId,
        SlotId allocationSlotId,
        List<SubjectId> subjectIds,
        List<InstanceId> proxyInstanceIds,
        PresenceId sourcePresenceId,
        InstanceId targetInstanceId,
        ResolvedManifestId targetResolvedManifestId,
        Instant requestedAt,
        Instant deadlineAt,
        TraceEnvelope traceEnvelope) implements RouteAttemptCommand {
    public RequestRouteAttempt {
        routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
        routeId = Objects.requireNonNull(routeId, "routeId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        allocationSlotId = Objects.requireNonNull(allocationSlotId, "allocationSlotId");
        subjectIds = List.copyOf(Objects.requireNonNull(subjectIds, "subjectIds"));
        proxyInstanceIds = List.copyOf(Objects.requireNonNull(proxyInstanceIds, "proxyInstanceIds"));
        sourcePresenceId = Objects.requireNonNull(sourcePresenceId, "sourcePresenceId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        targetResolvedManifestId = Objects.requireNonNull(targetResolvedManifestId, "targetResolvedManifestId");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
