package sh.harold.fulcrum.host.effect;

import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectPayload;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EffectAdmissionPolicy(List<EffectAdmissionRule> platformRules) {
    public EffectAdmissionPolicy {
        platformRules = List.copyOf(Objects.requireNonNull(platformRules, "platformRules"));
    }

    public static EffectAdmissionPolicy of(EffectAdmissionRule... platformRules) {
        return new EffectAdmissionPolicy(Arrays.stream(Objects.requireNonNull(platformRules, "platformRules")).toList());
    }

    Optional<EffectAdmissionRule> ruleFor(EffectEnvelope<? extends EffectPayload> effect) {
        return platformRules.stream()
                .filter(rule -> rule.matchesClassAndScope(effect))
                .findFirst();
    }
}
