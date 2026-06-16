package sh.harold.fulcrum.data.session;

import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SessionSnapshot(
        SessionId sessionId,
        ExperienceId experienceId,
        SlotId slotId,
        InstanceId ownerInstanceId,
        SessionOwnerToken ownerToken,
        long ownerEpoch,
        ResolvedManifestId resolvedManifestId,
        SessionLifecycleStatus status,
        Instant openedAt,
        Instant leaseExpiresAt,
        Optional<Instant> activatedAt,
        Optional<Instant> closedAt,
        Optional<SessionCloseReason> closeReason) {
    public SessionSnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        slotId = Objects.requireNonNull(slotId, "slotId");
        ownerInstanceId = Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (ownerEpoch <= 0) {
            throw new IllegalArgumentException("ownerEpoch must be positive");
        }
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        status = Objects.requireNonNull(status, "status");
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        activatedAt = activatedAt == null ? Optional.empty() : activatedAt;
        closedAt = closedAt == null ? Optional.empty() : closedAt;
        closeReason = closeReason == null ? Optional.empty() : closeReason;
        if (!leaseExpiresAt.isAfter(openedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after openedAt");
        }
        if (status == SessionLifecycleStatus.PREPARING && (activatedAt.isPresent() || closedAt.isPresent() || closeReason.isPresent())) {
            throw new IllegalArgumentException("preparing Session cannot carry activation or close fields");
        }
        if (status == SessionLifecycleStatus.ACTIVE && (activatedAt.isEmpty() || closedAt.isPresent() || closeReason.isPresent())) {
            throw new IllegalArgumentException("active Session must carry activation and no close fields");
        }
        if (activatedAt.isPresent() && activatedAt.orElseThrow().isBefore(openedAt)) {
            throw new IllegalArgumentException("activatedAt must not be before openedAt");
        }
        if (isTerminal(status) && (closedAt.isEmpty() || closeReason.isEmpty())) {
            throw new IllegalArgumentException("terminal Session must carry close fields");
        }
        if (closedAt.isPresent() && closedAt.orElseThrow().isBefore(openedAt)) {
            throw new IllegalArgumentException("closedAt must not be before openedAt");
        }
        if (closeReason.isPresent() && closeReason.orElseThrow().terminalStatus() != status) {
            throw new IllegalArgumentException("close reason must match terminal Session status");
        }
    }

    static SessionSnapshot from(OpenSession command, long ownerEpoch) {
        return new SessionSnapshot(
                command.sessionId(),
                command.experienceId(),
                command.slotId(),
                command.ownerInstanceId(),
                command.ownerToken(),
                ownerEpoch,
                command.resolvedManifestId(),
                SessionLifecycleStatus.PREPARING,
                command.openedAt(),
                command.leaseExpiresAt(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    SessionSnapshot activate(ActivateSession command) {
        return new SessionSnapshot(
                sessionId,
                experienceId,
                slotId,
                ownerInstanceId,
                ownerToken,
                ownerEpoch,
                resolvedManifestId,
                SessionLifecycleStatus.ACTIVE,
                openedAt,
                command.leaseExpiresAt(),
                Optional.of(command.activatedAt()),
                Optional.empty(),
                Optional.empty());
    }

    SessionSnapshot heartbeat(HeartbeatSession command) {
        return new SessionSnapshot(
                sessionId,
                experienceId,
                slotId,
                ownerInstanceId,
                ownerToken,
                ownerEpoch,
                resolvedManifestId,
                status,
                openedAt,
                command.leaseExpiresAt(),
                activatedAt,
                Optional.empty(),
                Optional.empty());
    }

    SessionSnapshot close(CloseSession command) {
        return close(command.closedAt(), command.reason());
    }

    SessionSnapshot expire(ExpireSession command) {
        return close(command.expiredAt(), SessionCloseReason.LEASE_EXPIRED);
    }

    String wireValue() {
        return "sessionId=" + sessionId.value()
                + "\nexperienceId=" + experienceId.value()
                + "\nslotId=" + slotId.value()
                + "\nownerInstanceId=" + ownerInstanceId.value()
                + "\nownerToken=" + ownerToken.value()
                + "\nownerEpoch=" + ownerEpoch
                + "\nresolvedManifestId=" + resolvedManifestId.value()
                + "\nstatus=" + status.name()
                + "\nopenedAt=" + openedAt
                + "\nleaseExpiresAt=" + leaseExpiresAt
                + "\nactivatedAt=" + activatedAt.map(Instant::toString).orElse("")
                + "\nclosedAt=" + closedAt.map(Instant::toString).orElse("")
                + "\ncloseReason=" + closeReason.map(SessionCloseReason::name).orElse("");
    }

    private SessionSnapshot close(Instant closedAt, SessionCloseReason reason) {
        return new SessionSnapshot(
                sessionId,
                experienceId,
                slotId,
                ownerInstanceId,
                ownerToken,
                ownerEpoch,
                resolvedManifestId,
                reason.terminalStatus(),
                openedAt,
                leaseExpiresAt,
                activatedAt,
                Optional.of(closedAt),
                Optional.of(reason));
    }

    private static boolean isTerminal(SessionLifecycleStatus status) {
        return status == SessionLifecycleStatus.ENDED || status == SessionLifecycleStatus.FAILED;
    }
}
