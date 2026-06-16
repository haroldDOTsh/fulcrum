package sh.harold.fulcrum.standard.punishment;

import java.util.Objects;
import java.util.Optional;

public final class PunishmentLoginGate {
    private PunishmentLoginGate() {
    }

    public static PunishmentLoginDecision evaluate(
            PunishmentLoginRequest request,
            Optional<ActivePunishmentSnapshot> activePunishment) {
        PunishmentLoginRequest checkedRequest = Objects.requireNonNull(request, "request");
        Optional<ActivePunishmentSnapshot> checkedPunishment =
                Objects.requireNonNull(activePunishment, "activePunishment");
        if (checkedPunishment.isEmpty()) {
            return PunishmentLoginDecision.allowed(checkedRequest.subjectId());
        }

        ActivePunishmentSnapshot snapshot = checkedPunishment.orElseThrow();
        if (!snapshot.subjectId().equals(checkedRequest.subjectId())) {
            throw new IllegalArgumentException("active punishment projection row must match login Subject");
        }
        if (!snapshot.activeAt(checkedRequest.attemptedAt())) {
            return PunishmentLoginDecision.allowed(checkedRequest.subjectId());
        }
        return PunishmentLoginDecision.denied(checkedRequest.subjectId(), snapshot.reason());
    }
}
