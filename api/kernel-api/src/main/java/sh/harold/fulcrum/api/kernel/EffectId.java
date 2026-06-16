package sh.harold.fulcrum.api.kernel;

public record EffectId(String value) {
    public EffectId {
        value = Ids.requireNonBlank(value, "effectId");
    }
}
