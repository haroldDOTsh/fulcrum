package sh.harold.fulcrum.capability.api;

import java.util.List;
import java.util.Objects;

public record CapabilityValidationResult(List<CapabilityValidationError> errors) {
    public CapabilityValidationResult {
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
