package sh.harold.fulcrum.host.api;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HostObservationFactoryTest {
    private static final Instant NOW = Instant.parse("2026-06-16T20:00:00Z");
    private static final HostInstanceIdentity PAPER_IDENTITY = new HostInstanceIdentity(
            new InstanceId("instance-paper-1"),
            HostInstanceKinds.PAPER,
            new PoolId("pool-paper-small"),
            new MachineRef("machine-a"),
            new PrincipalId("principal-paper-1"));

    @Test
    void readinessObservationCarriesInstanceIdentityAndManifest() {
        HostObservation observation = HostObservationFactory.readiness(new HostReadinessReport(
                PAPER_IDENTITY,
                new ResolvedManifestId("manifest-session-1"),
                traceEnvelope(),
                NOW));

        assertEquals(PAPER_IDENTITY.instanceId(), observation.instanceId());
        assertEquals(HostObservationTypes.READINESS, observation.observationType());
        assertEquals(NOW, observation.observedAt());
        assertEquals("paper", observation.attributes().get("instanceKind"));
        assertEquals("pool-paper-small", observation.attributes().get("poolId"));
        assertEquals("machine-a", observation.attributes().get("machineRef"));
        assertEquals("principal-paper-1", observation.attributes().get("principalId"));
        assertEquals("manifest-session-1", observation.attributes().get("resolvedManifestId"));
        assertThrows(UnsupportedOperationException.class, () -> observation.attributes().put("other", "value"));
    }

    @Test
    void sessionAttachedObservationCarriesRouteSubjectAndSession() {
        SubjectId subjectId = new SubjectId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        HostObservation observation = HostObservationFactory.sessionAttached(new HostSessionAttachment(
                PAPER_IDENTITY,
                new RouteId("route-attach-1"),
                subjectId,
                new SessionId("session-attach-1"),
                traceEnvelope(),
                NOW));

        assertEquals(PAPER_IDENTITY.instanceId(), observation.instanceId());
        assertEquals(HostObservationTypes.SESSION_ATTACHED, observation.observationType());
        assertEquals("route-attach-1", observation.attributes().get("routeId"));
        assertEquals(subjectId.value().toString(), observation.attributes().get("subjectId"));
        assertEquals("session-attach-1", observation.attributes().get("sessionId"));
    }

    private static TraceEnvelope traceEnvelope() {
        return new TraceEnvelope(
                "trace-host-observation",
                "span-host-observation",
                Optional.empty(),
                NOW,
                "host-observation-test",
                new InstanceId("instance-host-observation-test"));
    }
}
