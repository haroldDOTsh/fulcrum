package sh.harold.fulcrum.api.kernel;

public record InstanceId(String value) {
    public InstanceId {
        value = Ids.requireNonBlank(value, "instanceId");
    }
}
