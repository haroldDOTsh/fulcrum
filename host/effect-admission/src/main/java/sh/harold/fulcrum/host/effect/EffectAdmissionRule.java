package sh.harold.fulcrum.host.effect;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.host.api.HostResourceGrant;

import java.util.Objects;
import java.util.Optional;

public record EffectAdmissionRule(
        EffectClass effectClass,
        String targetScopePrefix,
        Optional<CapabilityId> requiredCapability,
        HostResourceGrant requiredGrant) {
    public EffectAdmissionRule {
        effectClass = Objects.requireNonNull(effectClass, "effectClass");
        targetScopePrefix = requireNonBlank(targetScopePrefix, "targetScopePrefix");
        requiredCapability = requiredCapability == null ? Optional.empty() : requiredCapability;
        requiredGrant = Objects.requireNonNull(requiredGrant, "requiredGrant");
    }

    boolean matchesClassAndScope(EffectEnvelope<? extends EffectPayload> effect) {
        return effectClass == effect.effectClass()
                && effect.targetScope().value().startsWith(targetScopePrefix);
    }

    boolean matchesCapability(EffectEnvelope<? extends EffectPayload> effect) {
        return requiredCapability.isEmpty() || requiredCapability.equals(effect.requiredCapability());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
