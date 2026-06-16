package sh.harold.fulcrum.adapters.agones.fake;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostReadinessReport;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FakeAgonesAllocationAdapterTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-06-16T18:00:00Z");
    private static final PoolId POOL_ID = new PoolId("pool-paper-small");
    private static final ResolvedManifestId MANIFEST_ID = new ResolvedManifestId("manifest-session-1");

    @Test
    void allocationClaimsExactlyOneReadyPaperInstanceForOneSession() {
        FakeAgonesAllocationAdapter adapter = new FakeAgonesAllocationAdapter();
        HostReadinessReport report = readyReport("instance-paper-1", HostInstanceKinds.PAPER);
        adapter.registerReadyPaperInstance(report);

        HostAllocationClaim claim = adapter.allocate(allocationRequest("session-1"));

        assertEquals(new SessionId("session-1"), claim.sessionId());
        assertEquals(report.instanceIdentity(), claim.instanceIdentity());
        assertEquals(MANIFEST_ID, claim.resolvedManifestId());
        assertEquals("slot-instance-paper-1", claim.slotId().value());
        assertEquals(Optional.of(claim), adapter.activeClaim(new InstanceId("instance-paper-1")));
        assertThrows(IllegalStateException.class, () -> adapter.allocate(allocationRequest("session-2")));
    }

    @Test
    void allocatedPaperInstanceCannotBeRegisteredAgainForAnotherSession() {
        FakeAgonesAllocationAdapter adapter = new FakeAgonesAllocationAdapter();
        HostReadinessReport report = readyReport("instance-paper-1", HostInstanceKinds.PAPER);
        adapter.registerReadyPaperInstance(report);
        adapter.allocate(allocationRequest("session-1"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> adapter.registerReadyPaperInstance(report));

        assertTrue(failure.getMessage().contains("Instance already registered or allocated"));
    }

    @Test
    void fakeAgonesAdapterRejectsNonPaperReadyReports() {
        FakeAgonesAllocationAdapter adapter = new FakeAgonesAllocationAdapter();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> adapter.registerReadyPaperInstance(readyReport("instance-velocity-1", HostInstanceKinds.VELOCITY)));

        assertTrue(failure.getMessage().contains("Ready Paper Instances"));
    }

    private static HostAllocationRequest allocationRequest(String sessionId) {
        return new HostAllocationRequest(
                POOL_ID,
                new SessionId(sessionId),
                MANIFEST_ID,
                traceEnvelope("trace-" + sessionId),
                REQUESTED_AT);
    }

    private static HostReadinessReport readyReport(String instanceId, String kind) {
        return new HostReadinessReport(
                new HostInstanceIdentity(
                        new InstanceId(instanceId),
                        kind,
                        POOL_ID,
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-" + instanceId)),
                MANIFEST_ID,
                traceEnvelope("trace-ready-" + instanceId),
                REQUESTED_AT.minusSeconds(1));
    }

    private static TraceEnvelope traceEnvelope(String traceId) {
        return new TraceEnvelope(
                traceId,
                "span-agones-fake",
                Optional.empty(),
                REQUESTED_AT,
                "agones-fake-test",
                new InstanceId("instance-test-runner"));
    }
}
