package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.Optional;

public record PunishmentLoginDecision(
        SubjectId subjectId,
        boolean allowed,
        Optional<String> denialReason) {
    public PunishmentLoginDecision {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        denialReason = denialReason == null ? Optional.empty() : denialReason.map(PunishmentLoginDecision::requireNonBlank);
        if (!allowed && denialReason.isEmpty()) {
            throw new IllegalArgumentException("denied login decision requires a reason");
        }
    }

    public static PunishmentLoginDecision allowed(SubjectId subjectId) {
        return new PunishmentLoginDecision(subjectId, true, Optional.empty());
    }

    public static PunishmentLoginDecision denied(SubjectId subjectId, String reason) {
        return new PunishmentLoginDecision(subjectId, false, Optional.of(requireNonBlank(reason, "reason")));
    }

    private static String requireNonBlank(String value) {
        return requireNonBlank(value, "denialReason");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
