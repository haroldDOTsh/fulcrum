package sh.harold.fulcrum.core.session;

import java.util.Objects;

public record ClassifiedEffect(
        EffectDestination destination,
        EffectEnvelope<? extends EffectPayload> effect) {
    public ClassifiedEffect {
        destination = Objects.requireNonNull(destination, "destination");
        effect = Objects.requireNonNull(effect, "effect");
    }
}
