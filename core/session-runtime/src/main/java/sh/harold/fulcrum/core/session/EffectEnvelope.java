package sh.harold.fulcrum.core.session;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.EffectId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record EffectEnvelope<P extends EffectPayload>(
        EffectId effectId,
        IdempotencyKey idempotencyKey,
        EffectOrigin origin,
        TraceEnvelope traceEnvelope,
        Optional<CapabilityId> requiredCapability,
        EffectTargetScope targetScope,
        EffectClass effectClass,
        String payloadType,
        P payload,
        Instant issuedAt,
        Optional<Instant> deadlineAt,
        EffectSettlementMode settlementMode) {
    public EffectEnvelope {
        effectId = Objects.requireNonNull(effectId, "effectId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        origin = Objects.requireNonNull(origin, "origin");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        requiredCapability = requiredCapability == null ? Optional.empty() : requiredCapability;
        targetScope = Objects.requireNonNull(targetScope, "targetScope");
        effectClass = Objects.requireNonNull(effectClass, "effectClass");
        payload = Objects.requireNonNull(payload, "payload");
        payloadType = RuntimeNames.requireNonBlank(payloadType, "payloadType");
        if (!payloadType.equals(payload.payloadType())) {
            throw new IllegalArgumentException("payloadType must match payload.payloadType()");
        }
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        deadlineAt = deadlineAt == null ? Optional.empty() : deadlineAt;
        settlementMode = Objects.requireNonNull(settlementMode, "settlementMode");
    }

    public static <P extends EffectPayload> EffectEnvelope<P> issue(
            EffectId effectId,
            IdempotencyKey idempotencyKey,
            EffectOrigin origin,
            TraceEnvelope traceEnvelope,
            Optional<CapabilityId> requiredCapability,
            EffectTargetScope targetScope,
            EffectClass effectClass,
            P payload,
            Instant issuedAt,
            Optional<Instant> deadlineAt,
            EffectSettlementMode settlementMode) {
        return new EffectEnvelope<>(
                effectId,
                idempotencyKey,
                origin,
                traceEnvelope,
                requiredCapability,
                targetScope,
                effectClass,
                payload.payloadType(),
                payload,
                issuedAt,
                deadlineAt,
                settlementMode);
    }
}
