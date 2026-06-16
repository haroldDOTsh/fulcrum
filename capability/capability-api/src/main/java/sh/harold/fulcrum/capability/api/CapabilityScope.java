package sh.harold.fulcrum.capability.api;

public record CapabilityScope(String value) {
    public CapabilityScope {
        value = CapabilityNames.requireNonBlank(value, "scope");
    }
}
