package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.Optional;

public record VelocityLoginGateDecision(
        SubjectId subjectId,
        boolean allowed,
        Optional<String> denialReason) {
    public VelocityLoginGateDecision {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        denialReason = denialReason == null
                ? Optional.empty()
                : denialReason.map(VelocityLoginGateDecision::requireNonBlank);
        if (!allowed && denialReason.isEmpty()) {
            throw new IllegalArgumentException("denied login decision requires a reason");
        }
    }

    public static VelocityLoginGateDecision allowed(SubjectId subjectId) {
        return new VelocityLoginGateDecision(subjectId, true, Optional.empty());
    }

    public static VelocityLoginGateDecision denied(SubjectId subjectId, String reason) {
        return new VelocityLoginGateDecision(subjectId, false, Optional.of(requireNonBlank(reason)));
    }

    private static String requireNonBlank(String value) {
        String checked = Objects.requireNonNull(value, "denialReason").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("denialReason must not be blank");
        }
        return checked;
    }
}
