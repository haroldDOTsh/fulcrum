package sh.harold.fulcrum.host.effect;

import sh.harold.fulcrum.core.session.ClassifiedEffect;
import sh.harold.fulcrum.core.session.EffectClassifier;
import sh.harold.fulcrum.core.session.EffectDestination;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectOrigin;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.api.HostSessionAttachment;

import java.util.Objects;
import java.util.Optional;

public final class EffectAdmissionGate {
    private final EffectAdmissionPolicy policy;
    private final EffectClassifier classifier;

    public EffectAdmissionGate(EffectAdmissionPolicy policy) {
        this(policy, new EffectClassifier());
    }

    EffectAdmissionGate(EffectAdmissionPolicy policy, EffectClassifier classifier) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    public EffectAdmissionReceipt admit(
            HostSecurityContext securityContext,
            HostSessionAttachment attachment,
            EffectEnvelope<? extends EffectPayload> effect) {
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(attachment, "attachment");
        Objects.requireNonNull(effect, "effect");

        if (!securityContext.identity().equals(attachment.instanceIdentity())) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.SECURITY_CONTEXT_MISMATCH);
        }
        if (!effect.traceEnvelope().originInstanceId().equals(securityContext.identity().instanceId())) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.TRACE_INSTANCE_MISMATCH);
        }
        if (!EffectOrigin.SESSION.equals(effect.origin().originType())
                || !attachment.sessionId().value().equals(effect.origin().originId())) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.ORIGIN_SESSION_MISMATCH);
        }

        ClassifiedEffect classified;
        try {
            classified = classifier.classify(effect);
        } catch (IllegalArgumentException ignored) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.INVALID_SETTLEMENT);
        }
        if (classified.destination() != EffectDestination.PLATFORM) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.HOST_LOCAL_EFFECT);
        }

        Optional<EffectAdmissionRule> maybeRule = policy.ruleFor(effect);
        if (maybeRule.isEmpty()) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.UNDECLARED_PLATFORM_SCOPE);
        }

        EffectAdmissionRule rule = maybeRule.orElseThrow();
        if (!rule.matchesCapability(effect)) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.CAPABILITY_SCOPE_MISMATCH, rule);
        }
        if (!securityContext.credentialScope().permits(rule.requiredGrant())) {
            return reject(securityContext, effect, EffectAdmissionRejectionReason.MISSING_HOST_GRANT, rule);
        }
        return EffectAdmissionReceipt.accepted(securityContext, effect, rule.requiredGrant());
    }

    private static EffectAdmissionReceipt reject(
            HostSecurityContext securityContext,
            EffectEnvelope<? extends EffectPayload> effect,
            EffectAdmissionRejectionReason reason) {
        return EffectAdmissionReceipt.rejected(securityContext, effect, reason, Optional.empty());
    }

    private static EffectAdmissionReceipt reject(
            HostSecurityContext securityContext,
            EffectEnvelope<? extends EffectPayload> effect,
            EffectAdmissionRejectionReason reason,
            EffectAdmissionRule rule) {
        return EffectAdmissionReceipt.rejected(securityContext, effect, reason, Optional.of(rule.requiredGrant()));
    }
}
