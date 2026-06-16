package sh.harold.fulcrum.host.effect;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.EffectId;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectOrigin;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.core.session.EffectSettlementMode;
import sh.harold.fulcrum.core.session.EffectTargetScope;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.util.Objects;
import java.util.Optional;

public record EffectAdmissionReceipt(
        EffectAdmissionStatus status,
        Optional<EffectAdmissionRejectionReason> rejectionReason,
        EffectId effectId,
        IdempotencyKey idempotencyKey,
        PrincipalId authenticatedPrincipal,
        EffectOrigin origin,
        EffectTargetScope targetScope,
        EffectClass effectClass,
        EffectSettlementMode settlementMode,
        TraceEnvelope traceEnvelope,
        Optional<CapabilityId> requiredCapability,
        Optional<HostResourceGrant> requiredGrant) {
    public EffectAdmissionReceipt {
        status = Objects.requireNonNull(status, "status");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        effectId = Objects.requireNonNull(effectId, "effectId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        authenticatedPrincipal = Objects.requireNonNull(authenticatedPrincipal, "authenticatedPrincipal");
        origin = Objects.requireNonNull(origin, "origin");
        targetScope = Objects.requireNonNull(targetScope, "targetScope");
        effectClass = Objects.requireNonNull(effectClass, "effectClass");
        settlementMode = Objects.requireNonNull(settlementMode, "settlementMode");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        requiredCapability = requiredCapability == null ? Optional.empty() : requiredCapability;
        requiredGrant = requiredGrant == null ? Optional.empty() : requiredGrant;
        if (status == EffectAdmissionStatus.ACCEPTED && rejectionReason.isPresent()) {
            throw new IllegalArgumentException("accepted admission receipt cannot have a rejection reason");
        }
        if (status == EffectAdmissionStatus.REJECTED && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("rejected admission receipt requires a rejection reason");
        }
    }

    static EffectAdmissionReceipt accepted(
            HostSecurityContext securityContext,
            EffectEnvelope<? extends EffectPayload> effect,
            HostResourceGrant requiredGrant) {
        return receipt(
                EffectAdmissionStatus.ACCEPTED,
                Optional.empty(),
                securityContext,
                effect,
                Optional.of(requiredGrant));
    }

    static EffectAdmissionReceipt rejected(
            HostSecurityContext securityContext,
            EffectEnvelope<? extends EffectPayload> effect,
            EffectAdmissionRejectionReason reason,
            Optional<HostResourceGrant> requiredGrant) {
        return receipt(
                EffectAdmissionStatus.REJECTED,
                Optional.of(Objects.requireNonNull(reason, "reason")),
                securityContext,
                effect,
                requiredGrant);
    }

    private static EffectAdmissionReceipt receipt(
            EffectAdmissionStatus status,
            Optional<EffectAdmissionRejectionReason> reason,
            HostSecurityContext securityContext,
            EffectEnvelope<? extends EffectPayload> effect,
            Optional<HostResourceGrant> requiredGrant) {
        return new EffectAdmissionReceipt(
                status,
                reason,
                effect.effectId(),
                effect.idempotencyKey(),
                securityContext.identity().principalId(),
                effect.origin(),
                effect.targetScope(),
                effect.effectClass(),
                effect.settlementMode(),
                effect.traceEnvelope(),
                effect.requiredCapability(),
                requiredGrant);
    }
}
