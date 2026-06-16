package sh.harold.fulcrum.capability.api;

public record CapabilityVersion(String value) {
    public CapabilityVersion {
        value = CapabilityNames.requireNonBlank(value, "version");
        if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("version must use major.minor.patch");
        }
    }
}
