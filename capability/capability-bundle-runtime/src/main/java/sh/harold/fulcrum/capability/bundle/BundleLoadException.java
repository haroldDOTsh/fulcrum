package sh.harold.fulcrum.capability.bundle;

import java.util.Objects;
import java.util.Optional;

public final class BundleLoadException extends RuntimeException {
    private final Optional<BundleLoadDecision> decision;

    public BundleLoadException(String message) {
        super(message);
        this.decision = Optional.empty();
    }

    public BundleLoadException(String message, Throwable cause) {
        super(message, cause);
        this.decision = Optional.empty();
    }

    public BundleLoadException(BundleLoadDecision decision) {
        super(Objects.requireNonNull(decision, "decision")
                .refusalReason()
                .orElse("bundle load refused"));
        this.decision = Optional.of(decision);
    }

    public Optional<BundleLoadDecision> decision() {
        return decision;
    }
}
