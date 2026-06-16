package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.route.contract.AcknowledgeRoute;
import sh.harold.fulcrum.data.route.contract.OpenRoute;
import sh.harold.fulcrum.data.route.contract.TimeoutRoute;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RouteSnapshot(
        RouteId routeId,
        SubjectId subjectId,
        SessionId targetSessionId,
        InstanceId targetInstanceId,
        RouteLifecycleStatus status,
        Instant requestedAt,
        Instant expiresAt,
        Optional<Instant> completedAt) {
    public RouteSnapshot {
        routeId = Objects.requireNonNull(routeId, "routeId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        targetSessionId = Objects.requireNonNull(targetSessionId, "targetSessionId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        status = Objects.requireNonNull(status, "status");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        completedAt = completedAt == null ? Optional.empty() : completedAt;
        if (!expiresAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException("expiresAt must be after requestedAt");
        }
        if (status == RouteLifecycleStatus.PENDING && completedAt.isPresent()) {
            throw new IllegalArgumentException("pending Route cannot carry completion time");
        }
        if (status != RouteLifecycleStatus.PENDING && completedAt.isEmpty()) {
            throw new IllegalArgumentException("closed Route must carry completion time");
        }
    }

    static RouteSnapshot from(OpenRoute command) {
        return new RouteSnapshot(
                command.routeId(),
                command.subjectId(),
                command.targetSessionId(),
                command.targetInstanceId(),
                RouteLifecycleStatus.PENDING,
                command.requestedAt(),
                command.expiresAt(),
                Optional.empty());
    }

    RouteSnapshot acknowledge(AcknowledgeRoute command) {
        return new RouteSnapshot(
                routeId,
                subjectId,
                targetSessionId,
                targetInstanceId,
                RouteLifecycleStatus.ACKNOWLEDGED,
                requestedAt,
                expiresAt,
                Optional.of(command.acknowledgedAt()));
    }

    RouteSnapshot timeout(TimeoutRoute command) {
        return new RouteSnapshot(
                routeId,
                subjectId,
                targetSessionId,
                targetInstanceId,
                RouteLifecycleStatus.TIMED_OUT,
                requestedAt,
                expiresAt,
                Optional.of(command.timedOutAt()));
    }

    String wireValue() {
        return "routeId=" + routeId.value()
                + "\nsubjectId=" + subjectId.value()
                + "\ntargetSessionId=" + targetSessionId.value()
                + "\ntargetInstanceId=" + targetInstanceId.value()
                + "\nstatus=" + status.name()
                + "\nrequestedAt=" + requestedAt
                + "\nexpiresAt=" + expiresAt
                + "\ncompletedAt=" + completedAt.map(Instant::toString).orElse("");
    }
}
