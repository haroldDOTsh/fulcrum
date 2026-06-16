package sh.harold.fulcrum.api.contract;

public record IdempotencyKey(String value) {
    public IdempotencyKey {
        value = Names.requireNonBlank(value, "idempotencyKey");
    }
}
