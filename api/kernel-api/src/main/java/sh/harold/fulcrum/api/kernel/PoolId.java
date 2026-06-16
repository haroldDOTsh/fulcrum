package sh.harold.fulcrum.api.kernel;

public record PoolId(String value) {
    public PoolId {
        value = Ids.requireNonBlank(value, "poolId");
    }
}
