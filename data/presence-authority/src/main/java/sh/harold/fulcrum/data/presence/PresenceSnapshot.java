package sh.harold.fulcrum.data.presence;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PresenceSnapshot(
        PresenceId presenceId,
        SubjectId subjectId,
        InstanceId ownerInstanceId,
        PresenceOwnerToken ownerToken,
        long ownerEpoch,
        PresenceLifecycleStatus status,
        Optional<SessionId> sessionId,
        Optional<RouteId> routeId,
        Instant observedAt,
        Instant expiresAt,
        Optional<Instant> releasedAt,
        Optional<PresenceReleaseReason> releaseReason) {
    public PresenceSnapshot {
        presenceId = Objects.requireNonNull(presenceId, "presenceId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        sessionId = sessionId == null ? Optional.empty() : sessionId;
        routeId = routeId == null ? Optional.empty() : routeId;
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        releasedAt = releasedAt == null ? Optional.empty() : releasedAt;
        releaseReason = releaseReason == null ? Optional.empty() : releaseReason;
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must be after observedAt");
        }
        if (status == PresenceLifecycleStatus.LIVE && (releasedAt.isPresent() || releaseReason.isPresent())) {
            throw new IllegalArgumentException("live Presence cannot carry release fields");
        }
        if (status == PresenceLifecycleStatus.RELEASED && (releasedAt.isEmpty() || releaseReason.isEmpty())) {
            throw new IllegalArgumentException("released Presence must carry release fields");
        }
    }

    static PresenceSnapshot from(ClaimPresence command, long ownerEpoch) {
        return new PresenceSnapshot(
                command.presenceId(),
                command.subjectId(),
                command.ownerInstanceId(),
                command.ownerToken(),
                ownerEpoch,
                PresenceLifecycleStatus.LIVE,
                command.sessionId(),
                command.routeId(),
                command.observedAt(),
                command.expiresAt(),
                Optional.empty(),
                Optional.empty());
    }

    PresenceSnapshot heartbeat(HeartbeatPresence command) {
        return new PresenceSnapshot(
                presenceId,
                subjectId,
                ownerInstanceId,
                ownerToken,
                ownerEpoch,
                PresenceLifecycleStatus.LIVE,
                sessionId,
                routeId,
                command.observedAt(),
                command.expiresAt(),
                Optional.empty(),
                Optional.empty());
    }

    PresenceSnapshot release(ReleasePresence command) {
        return new PresenceSnapshot(
                presenceId,
                subjectId,
                ownerInstanceId,
                ownerToken,
                ownerEpoch,
                PresenceLifecycleStatus.RELEASED,
                sessionId,
                routeId,
                observedAt,
                expiresAt,
                Optional.of(command.releasedAt()),
                Optional.of(command.reason()));
    }

    String wireValue() {
        return "presenceId=" + presenceId.value()
                + "\nsubjectId=" + subjectId.value()
                + "\nownerInstanceId=" + ownerInstanceId.value()
                + "\nownerToken=" + ownerToken.value()
                + "\nownerEpoch=" + ownerEpoch
                + "\nstatus=" + status.name()
                + "\nsessionId=" + sessionId.map(SessionId::value).orElse("")
                + "\nrouteId=" + routeId.map(RouteId::value).orElse("")
                + "\nobservedAt=" + observedAt
                + "\nexpiresAt=" + expiresAt
                + "\nreleasedAt=" + releasedAt.map(Instant::toString).orElse("")
                + "\nreleaseReason=" + releaseReason.map(PresenceReleaseReason::name).orElse("");
    }
}
