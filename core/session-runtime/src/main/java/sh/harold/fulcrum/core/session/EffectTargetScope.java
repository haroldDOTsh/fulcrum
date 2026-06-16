package sh.harold.fulcrum.core.session;

public record EffectTargetScope(String value) {
    public EffectTargetScope {
        value = RuntimeNames.requireNonBlank(value, "targetScope");
    }
}
