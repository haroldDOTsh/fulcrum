package sh.harold.fulcrum.core.session;

import java.util.List;
import java.util.Objects;

public final class EffectClassifier {
    public ClassifiedEffect classify(EffectEnvelope<? extends EffectPayload> effect) {
        Objects.requireNonNull(effect, "effect");
        if (effect.effectClass() == EffectClass.HOST_LOCAL) {
            if (effect.settlementMode() != EffectSettlementMode.HOST_INLINE) {
                throw new IllegalArgumentException("Host-local Effects must use host-inline settlement");
            }
            return new ClassifiedEffect(EffectDestination.HOST_LOCAL, effect);
        }

        if (effect.settlementMode() == EffectSettlementMode.HOST_INLINE) {
            throw new IllegalArgumentException("Platform Effects must not use host-inline settlement");
        }
        return new ClassifiedEffect(EffectDestination.PLATFORM, effect);
    }

    public List<ClassifiedEffect> classifyAll(List<EffectEnvelope<? extends EffectPayload>> effects) {
        Objects.requireNonNull(effects, "effects");
        return effects.stream().map(this::classify).toList();
    }
}
