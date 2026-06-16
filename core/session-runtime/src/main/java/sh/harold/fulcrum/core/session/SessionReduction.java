package sh.harold.fulcrum.core.session;

import java.util.List;
import java.util.Objects;

public record SessionReduction<S>(
        S state,
        List<EffectEnvelope<? extends EffectPayload>> effects) {
    public SessionReduction {
        state = Objects.requireNonNull(state, "state");
        effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
    }

    public static <S> SessionReduction<S> withoutEffects(S state) {
        return new SessionReduction<>(state, List.of());
    }

    public static <S> SessionReduction<S> withEffects(S state, List<EffectEnvelope<? extends EffectPayload>> effects) {
        return new SessionReduction<>(state, effects);
    }
}
