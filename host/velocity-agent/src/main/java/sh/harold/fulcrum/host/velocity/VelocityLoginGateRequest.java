package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record VelocityLoginGateRequest(
        SubjectId subjectId,
        String username,
        String loginGateScope,
        Instant attemptedAt) {
    public VelocityLoginGateRequest {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        username = requireNonBlank(username, "username");
        loginGateScope = requireNonBlank(loginGateScope, "loginGateScope");
        attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
