package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.EffectId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectOrigin;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.core.session.EffectSettlementMode;
import sh.harold.fulcrum.core.session.EffectTargetScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostSessionAttachment;

import java.time.Instant;
import java.util.Optional;

final class TickRuntimeFixtures {
    static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    static final SessionId SESSION_ID = new SessionId("session-runtime");

    private TickRuntimeFixtures() {
    }

    static HostSessionAttachment attachment() {
        return new HostSessionAttachment(
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-runtime"),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-paper-runtime"),
                        new MachineRef("machine-runtime"),
                        new PrincipalId("principal-paper-runtime")),
                new RouteId("route-runtime"),
                new SubjectId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000123")),
                SESSION_ID,
                trace(),
                NOW);
    }

    static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-runtime",
                "span-runtime",
                Optional.empty(),
                NOW,
                "tick-runtime-test",
                new InstanceId("instance-runtime-test"));
    }

    static EffectEnvelope<TestPayload> effect(EffectClass effectClass, EffectSettlementMode settlementMode) {
        return EffectEnvelope.issue(
                new EffectId("effect-runtime-" + effectClass.name().toLowerCase()),
                new IdempotencyKey("idem-runtime-" + effectClass.name().toLowerCase()),
                EffectOrigin.session(SESSION_ID),
                trace(),
                Optional.empty(),
                new EffectTargetScope("session:" + SESSION_ID.value()),
                effectClass,
                new TestPayload("runtime-test"),
                NOW,
                Optional.empty(),
                settlementMode);
    }

    private record TestPayload(String value) implements EffectPayload {
        @Override
        public String payloadType() {
            return "fixture.runtime";
        }
    }
}
