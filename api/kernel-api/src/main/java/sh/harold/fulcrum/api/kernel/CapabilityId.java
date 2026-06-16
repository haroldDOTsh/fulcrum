package sh.harold.fulcrum.api.kernel;

public record CapabilityId(String value) {
    public CapabilityId {
        value = Ids.requireNonBlank(value, "capabilityId");
    }
}
