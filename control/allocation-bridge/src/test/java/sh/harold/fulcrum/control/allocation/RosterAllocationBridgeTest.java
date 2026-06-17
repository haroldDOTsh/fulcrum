package sh.harold.fulcrum.control.allocation;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.queue.QueueIntentId;
import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.control.queue.RosterIntentSnapshot;
import sh.harold.fulcrum.control.queue.RosterIntentStatus;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RosterAllocationBridgeTest {
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T13:00:00Z");
    private static final PoolId POOL_ID = new PoolId("pool-paper-arena");
    private static final ResolvedManifestId MANIFEST_ID = new ResolvedManifestId("manifest-arena-1");
    private static final SessionId SESSION_ID = new SessionId("session-arena-1");

    @Test
    void formedRosterMapsToHostAllocationRequestAndClaim() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION_ID, MANIFEST_ID, POOL_ID));
        RosterAllocationBridge bridge = new RosterAllocationBridge(port);

        RosterAllocationDecision decision = bridge.allocate(request("roster-1", SESSION_ID, MANIFEST_ID));

        assertEquals(RosterAllocationDecisionStatus.ACCEPTED, decision.status());
        assertEquals(1, port.callCount);
        assertEquals(POOL_ID, port.lastRequest.poolId());
        assertEquals(SESSION_ID, port.lastRequest.sessionId());
        assertEquals(MANIFEST_ID, port.lastRequest.resolvedManifestId());
        assertEquals("trace-roster", port.lastRequest.traceEnvelope().traceId());
        assertEquals(BASE_TIME.plusSeconds(1), port.lastRequest.requestedAt());
        assertEquals(new SlotId("slot-instance-paper-1"), decision.claim().orElseThrow().slotId());
        assertTrue(decision.emissions().stream().anyMatch(emission -> emission.kind() == RosterAllocationEmissionKind.HOST_ALLOCATION_REQUEST));
        assertTrue(decision.emissions().stream().anyMatch(emission -> emission.kind() == RosterAllocationEmissionKind.HOST_ALLOCATION_CLAIM));
        assertTrue(decision.receipt().wireValue().contains("minecraftHost=paper-arena.internal"));
        assertTrue(decision.receipt().wireValue().contains("minecraftPort=25567"));
    }

    @Test
    void duplicateRosterAllocationReplaysAcceptedClaimWithoutSecondPortCall() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION_ID, MANIFEST_ID, POOL_ID));
        RosterAllocationBridge bridge = new RosterAllocationBridge(port);
        RosterAllocationRequest request = request("roster-1", SESSION_ID, MANIFEST_ID);

        RosterAllocationDecision first = bridge.allocate(request);
        RosterAllocationDecision second = bridge.allocate(request);

        assertEquals(RosterAllocationDecisionStatus.ACCEPTED, first.status());
        assertEquals(RosterAllocationDecisionStatus.REPLAYED, second.status());
        assertEquals(first.receipt(), second.receipt());
        assertEquals(first.claim(), second.claim());
        assertTrue(second.emissions().isEmpty());
        assertEquals(1, port.callCount);
    }

    @Test
    void sameRosterWithDifferentSessionIsRejectedBeforeSecondPortCall() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION_ID, MANIFEST_ID, POOL_ID));
        RosterAllocationBridge bridge = new RosterAllocationBridge(port);

        bridge.allocate(request("roster-1", SESSION_ID, MANIFEST_ID));
        RosterAllocationDecision decision = bridge.allocate(request("roster-1", new SessionId("session-arena-2"), MANIFEST_ID));

        assertEquals(RosterAllocationDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(RosterAllocationRejectionReason.IDEMPOTENCY_CONFLICT), decision.receipt().rejectionReason());
        assertEquals(1, port.callCount);
    }

    @Test
    void unavailableAllocationIsRejectedWithoutPoisoningRetry() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION_ID, MANIFEST_ID, POOL_ID));
        port.fail = true;
        RosterAllocationBridge bridge = new RosterAllocationBridge(port);
        RosterAllocationRequest request = request("roster-1", SESSION_ID, MANIFEST_ID);

        RosterAllocationDecision unavailable = bridge.allocate(request);
        port.fail = false;
        RosterAllocationDecision accepted = bridge.allocate(request);

        assertEquals(RosterAllocationDecisionStatus.REJECTED, unavailable.status());
        assertEquals(Optional.of(RosterAllocationRejectionReason.ALLOCATION_UNAVAILABLE), unavailable.receipt().rejectionReason());
        assertEquals(RosterAllocationDecisionStatus.ACCEPTED, accepted.status());
        assertEquals(2, port.callCount);
    }

    @Test
    void invalidClaimIsRejectedAndNotStored() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(new SessionId("session-other"), MANIFEST_ID, POOL_ID));
        RosterAllocationBridge bridge = new RosterAllocationBridge(port);

        RosterAllocationDecision decision = bridge.allocate(request("roster-1", SESSION_ID, MANIFEST_ID));

        assertEquals(RosterAllocationDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(RosterAllocationRejectionReason.INVALID_ALLOCATION_CLAIM), decision.receipt().rejectionReason());
        assertEquals(1, port.callCount);
    }

    private static RosterAllocationRequest request(
            String rosterIntentId,
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId) {
        return new RosterAllocationRequest(roster(rosterIntentId), sessionId, resolvedManifestId, BASE_TIME.plusSeconds(1));
    }

    private static RosterIntentSnapshot roster(String rosterIntentId) {
        return new RosterIntentSnapshot(
                new RosterIntentId(rosterIntentId),
                List.of(new QueueIntentId("queue-1")),
                List.of(new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"))),
                new ExperienceId("experience-arena"),
                Optional.of("standard"),
                POOL_ID,
                1,
                RosterIntentStatus.FORMED,
                trace(),
                BASE_TIME);
    }

    private static HostAllocationClaim claim(
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            PoolId poolId) {
        return new HostAllocationClaim(
                new SlotId("slot-instance-paper-1"),
                sessionId,
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-1"),
                        HostInstanceKinds.PAPER,
                        poolId,
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-paper-1")),
                resolvedManifestId,
                new HostNetworkEndpoint("paper-arena.internal", 25_567),
                trace(),
                BASE_TIME.plusSeconds(1));
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-roster",
                "span-roster",
                Optional.empty(),
                BASE_TIME,
                "allocation-bridge-test",
                new InstanceId("instance-controller-allocation"));
    }

    private static final class RecordingAllocationPort implements HostAllocationPort {
        private final HostAllocationClaim claim;
        private int callCount;
        private HostAllocationRequest lastRequest;
        private boolean fail;

        private RecordingAllocationPort(HostAllocationClaim claim) {
            this.claim = claim;
        }

        @Override
        public HostAllocationClaim allocate(HostAllocationRequest request) {
            callCount++;
            lastRequest = request;
            if (fail) {
                throw new IllegalStateException("not ready");
            }
            return claim;
        }
    }
}
