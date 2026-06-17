package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.api.contract.Revision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RouteAttemptSnapshot(
        RouteAttemptId routeAttemptId,
        RouteId routeId,
        SessionId sessionId,
        SlotId allocationSlotId,
        List<SubjectId> subjectIds,
        List<InstanceId> proxyInstanceIds,
        PresenceId sourcePresenceId,
        InstanceId targetInstanceId,
        ResolvedManifestId targetResolvedManifestId,
        Instant deadlineAt,
        int retryCount,
        RouteAttemptLifecycleStatus status,
        Optional<String> failureReason,
        TraceEnvelope traceEnvelope,
        Instant updatedAt) {
    public RouteAttemptSnapshot {
        routeAttemptId = Objects.requireNonNull(routeAttemptId, "routeAttemptId");
        routeId = Objects.requireNonNull(routeId, "routeId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        allocationSlotId = Objects.requireNonNull(allocationSlotId, "allocationSlotId");
        subjectIds = List.copyOf(Objects.requireNonNull(subjectIds, "subjectIds"));
        if (subjectIds.isEmpty()) {
            throw new IllegalArgumentException("subjectIds must not be empty");
        }
        proxyInstanceIds = List.copyOf(Objects.requireNonNull(proxyInstanceIds, "proxyInstanceIds"));
        if (proxyInstanceIds.isEmpty()) {
            throw new IllegalArgumentException("proxyInstanceIds must not be empty");
        }
        sourcePresenceId = Objects.requireNonNull(sourcePresenceId, "sourcePresenceId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        targetResolvedManifestId = Objects.requireNonNull(targetResolvedManifestId, "targetResolvedManifestId");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be non-negative");
        }
        status = Objects.requireNonNull(status, "status");
        failureReason = failureReason == null ? Optional.empty() : failureReason.map(reason -> ControlRouteStrings.requireNonBlank(reason, "failureReason"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static RouteAttemptSnapshot from(RequestRouteAttempt request) {
        if (!request.deadlineAt().isAfter(request.requestedAt())) {
            throw new IllegalArgumentException("deadlineAt must be after requestedAt");
        }
        return new RouteAttemptSnapshot(
                request.routeAttemptId(),
                request.routeId(),
                request.sessionId(),
                request.allocationSlotId(),
                request.subjectIds(),
                request.proxyInstanceIds(),
                request.sourcePresenceId(),
                request.targetInstanceId(),
                request.targetResolvedManifestId(),
                request.deadlineAt(),
                0,
                RouteAttemptLifecycleStatus.CREATED,
                Optional.empty(),
                request.traceEnvelope(),
                request.requestedAt());
    }

    RouteAttemptSnapshot transition(
            RouteAttemptLifecycleStatus nextStatus,
            Optional<String> nextFailureReason,
            int nextRetryCount,
            Instant updatedAt,
            Instant nextDeadlineAt) {
        return new RouteAttemptSnapshot(
                routeAttemptId,
                routeId,
                sessionId,
                allocationSlotId,
                subjectIds,
                proxyInstanceIds,
                sourcePresenceId,
                targetInstanceId,
                targetResolvedManifestId,
                nextDeadlineAt,
                nextRetryCount,
                nextStatus,
                nextFailureReason,
                traceEnvelope,
                updatedAt);
    }

    public String wireValue(Revision revision) {
        return "routeAttemptId=" + routeAttemptId.value()
                + "|routeId=" + routeId.value()
                + "|sessionId=" + sessionId.value()
                + "|slotId=" + allocationSlotId.value()
                + "|targetInstanceId=" + targetInstanceId.value()
                + "|targetResolvedManifestId=" + targetResolvedManifestId.value()
                + "|status=" + status.name()
                + "|retryCount=" + retryCount
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }

    public String proxyCommandValue() {
        return "proxy.route"
                + "|routeAttemptId=" + routeAttemptId.value()
                + "|routeId=" + routeId.value()
                + "|subjectId=" + proxySubjectId().value()
                + "|sessionId=" + sessionId.value()
                + "|targetInstanceId=" + targetInstanceId.value()
                + "|traceId=" + traceEnvelope.traceId();
    }

    private SubjectId proxySubjectId() {
        if (subjectIds.size() != 1) {
            throw new IllegalStateException("proxy route command emission requires exactly one Subject");
        }
        return subjectIds.getFirst();
    }

    public String hostCommandValue() {
        return "host.route.prepare"
                + "|routeAttemptId=" + routeAttemptId.value()
                + "|routeId=" + routeId.value()
                + "|sessionId=" + sessionId.value()
                + "|resolvedManifestId=" + targetResolvedManifestId.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
