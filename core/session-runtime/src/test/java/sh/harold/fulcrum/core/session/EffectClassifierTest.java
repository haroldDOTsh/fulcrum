package sh.harold.fulcrum.core.session;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.EffectId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EffectClassifierTest {
    private final EffectClassifier classifier = new EffectClassifier();

    @Test
    void hostLocalEffectsStayHostLocal() {
        ClassifiedEffect classified = classifier.classify(effect(EffectClass.HOST_LOCAL, EffectSettlementMode.HOST_INLINE));

        assertEquals(EffectDestination.HOST_LOCAL, classified.destination());
    }

    @Test
    void platformEffectsAreNotHostInline() {
        ClassifiedEffect classified = classifier.classify(effect(EffectClass.AUTHORITY, EffectSettlementMode.ACCEPTED_ASYNC));

        assertEquals(EffectDestination.PLATFORM, classified.destination());
    }

    @Test
    void hostLocalEffectCannotUseAsyncSettlement() {
        EffectEnvelope<TestPayload> effect = effect(EffectClass.HOST_LOCAL, EffectSettlementMode.ACCEPTED_ASYNC);

        assertThrows(IllegalArgumentException.class, () -> classifier.classify(effect));
    }

    @Test
    void platformEffectCannotUseHostInlineSettlement() {
        EffectEnvelope<TestPayload> effect = effect(EffectClass.CONTROL_PLANE, EffectSettlementMode.HOST_INLINE);

        assertThrows(IllegalArgumentException.class, () -> classifier.classify(effect));
    }

    @Test
    void envelopeRequiresMatchingPayloadType() {
        TestPayload payload = new TestPayload("fixture.payload", "value");

        assertThrows(IllegalArgumentException.class, () -> new EffectEnvelope<>(
                new EffectId("effect-1"),
                new IdempotencyKey("idem-1"),
                EffectOrigin.session(new SessionId("session-1")),
                trace(),
                Optional.empty(),
                new EffectTargetScope("session:session-1"),
                EffectClass.HOST_LOCAL,
                "wrong.payload",
                payload,
                Instant.parse("2026-06-16T12:00:00Z"),
                Optional.empty(),
                EffectSettlementMode.HOST_INLINE));
    }

    private static EffectEnvelope<TestPayload> effect(EffectClass effectClass, EffectSettlementMode settlementMode) {
        return EffectEnvelope.issue(
                new EffectId("effect-1"),
                new IdempotencyKey("idem-1"),
                EffectOrigin.session(new SessionId("session-1")),
                trace(),
                Optional.empty(),
                new EffectTargetScope("session:session-1"),
                effectClass,
                new TestPayload("fixture.payload", "value"),
                Instant.parse("2026-06-16T12:00:00Z"),
                Optional.empty(),
                settlementMode);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-1",
                "span-1",
                Optional.empty(),
                Instant.parse("2026-06-16T12:00:00Z"),
                "session-runtime-test",
                new InstanceId("instance-test-1"));
    }

    private record TestPayload(String payloadType, String value) implements EffectPayload {
    }
}
