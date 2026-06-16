package sh.harold.fulcrum.capability.api;

public record CapabilityValidationError(String code, String detail) {
    public CapabilityValidationError {
        code = CapabilityNames.requireNonBlank(code, "code");
        detail = CapabilityNames.requireNonBlank(detail, "detail");
    }
}
