package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Clock;
import java.util.Objects;

final class VelocityLoginAdmissionHandler {
    static final String BRIDGE_UNAVAILABLE_REASON = "Login temporarily unavailable";

    private final VelocityLoginGateEvaluator evaluator;
    private final String loginGateScope;
    private final Clock clock;

    VelocityLoginAdmissionHandler(
            VelocityLoginGateEvaluator evaluator,
            String loginGateScope,
            Clock clock) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.loginGateScope = requireNonBlank(loginGateScope, "loginGateScope");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    VelocityLoginGateDecision evaluate(SubjectId subjectId, String username) {
        Objects.requireNonNull(subjectId, "subjectId");
        try {
            return evaluator.evaluate(new VelocityLoginGateRequest(
                    subjectId,
                    username,
                    loginGateScope,
                    clock.instant()));
        } catch (RuntimeException exception) {
            return VelocityLoginGateDecision.denied(subjectId, BRIDGE_UNAVAILABLE_REASON);
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
