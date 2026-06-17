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
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostNetworkEndpoint;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SharedShardAllocationBridgeTest {
    private static final Instant NOW = Instant.parse("2026-06-17T09:00:00Z");
    private static final ExperienceId EXPERIENCE = new ExperienceId("experience-lobby");
    private static final PoolId POOL = new PoolId("pool-lobby");
    private static final ResolvedManifestId MANIFEST = new ResolvedManifestId("manifest-lobby-bedrock");
    private static final SessionId SESSION = new SessionId("session-lobby-shared");

    @Test
    void allocatesOnePaperInstanceForSharedShardSession() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION, MANIFEST, POOL, HostInstanceKinds.PAPER));
        SharedShardAllocationBridge bridge = new SharedShardAllocationBridge(port);

        SharedShardAllocationDecision decision = bridge.allocate(request(SESSION, MANIFEST));

        assertEquals(SharedShardAllocationDecisionStatus.ACCEPTED, decision.status());
        assertEquals(1, port.callCount);
        assertEquals(POOL, port.lastRequest.poolId());
        assertEquals(SESSION, port.lastRequest.sessionId());
        assertEquals(MANIFEST, port.lastRequest.resolvedManifestId());
        assertTrue(decision.emissions().stream()
                .anyMatch(emission -> emission.kind() == SharedShardAllocationEmissionKind.HOST_ALLOCATION_REQUEST));
        assertTrue(decision.emissions().stream()
                .anyMatch(emission -> emission.kind() == SharedShardAllocationEmissionKind.HOST_ALLOCATION_CLAIM));
        SharedShardAllocationEmission claimEmission = decision.emissions().stream()
                .filter(emission -> emission.kind() == SharedShardAllocationEmissionKind.HOST_ALLOCATION_CLAIM)
                .findFirst()
                .orElseThrow();
        assertTrue(claimEmission.value().contains("minecraftHost=paper-lobby.internal"));
        assertTrue(claimEmission.value().contains("minecraftPort=25566"));
        assertTrue(decision.receipt().wireValue().contains("minecraftHost=paper-lobby.internal"));
        assertTrue(decision.receipt().wireValue().contains("minecraftPort=25566"));
    }

    @Test
    void duplicateSharedShardAllocationReplaysAcceptedClaimWithoutSecondPortCall() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION, MANIFEST, POOL, HostInstanceKinds.PAPER));
        SharedShardAllocationBridge bridge = new SharedShardAllocationBridge(port);
        SharedShardAllocationRequest request = request(SESSION, MANIFEST);

        SharedShardAllocationDecision first = bridge.allocate(request);
        SharedShardAllocationDecision second = bridge.allocate(request);

        assertEquals(SharedShardAllocationDecisionStatus.ACCEPTED, first.status());
        assertEquals(SharedShardAllocationDecisionStatus.REPLAYED, second.status());
        assertEquals(first.receipt(), second.receipt());
        assertEquals(first.claim(), second.claim());
        assertTrue(second.emissions().isEmpty());
        assertEquals(1, port.callCount);
    }

    @Test
    void sameSharedShardSessionWithDifferentManifestIsRejectedBeforeSecondPortCall() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION, MANIFEST, POOL, HostInstanceKinds.PAPER));
        SharedShardAllocationBridge bridge = new SharedShardAllocationBridge(port);

        bridge.allocate(request(SESSION, MANIFEST));
        SharedShardAllocationDecision decision =
                bridge.allocate(request(SESSION, new ResolvedManifestId("manifest-other")));

        assertEquals(SharedShardAllocationDecisionStatus.REJECTED, decision.status());
        assertEquals(
                Optional.of(SharedShardAllocationRejectionReason.IDEMPOTENCY_CONFLICT),
                decision.receipt().rejectionReason());
        assertEquals(1, port.callCount);
    }

    @Test
    void unavailableAllocationIsRejectedWithoutBlockingRetry() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION, MANIFEST, POOL, HostInstanceKinds.PAPER));
        port.fail = true;
        SharedShardAllocationBridge bridge = new SharedShardAllocationBridge(port);
        SharedShardAllocationRequest request = request(SESSION, MANIFEST);

        SharedShardAllocationDecision unavailable = bridge.allocate(request);
        port.fail = false;
        SharedShardAllocationDecision accepted = bridge.allocate(request);

        assertEquals(SharedShardAllocationDecisionStatus.REJECTED, unavailable.status());
        assertEquals(
                Optional.of(SharedShardAllocationRejectionReason.ALLOCATION_UNAVAILABLE),
                unavailable.receipt().rejectionReason());
        assertEquals(SharedShardAllocationDecisionStatus.ACCEPTED, accepted.status());
        assertEquals(2, port.callCount);
    }

    @Test
    void invalidClaimIsRejectedAndNotStored() {
        RecordingAllocationPort port = new RecordingAllocationPort(
                claim(new SessionId("session-other"), MANIFEST, POOL, HostInstanceKinds.PAPER));
        SharedShardAllocationBridge bridge = new SharedShardAllocationBridge(port);

        SharedShardAllocationDecision decision = bridge.allocate(request(SESSION, MANIFEST));

        assertEquals(SharedShardAllocationDecisionStatus.REJECTED, decision.status());
        assertEquals(
                Optional.of(SharedShardAllocationRejectionReason.INVALID_ALLOCATION_CLAIM),
                decision.receipt().rejectionReason());
        assertEquals(1, port.callCount);
    }

    @Test
    void nonPaperClaimIsRejected() {
        RecordingAllocationPort port = new RecordingAllocationPort(claim(SESSION, MANIFEST, POOL, HostInstanceKinds.WORKER));
        SharedShardAllocationBridge bridge = new SharedShardAllocationBridge(port);

        SharedShardAllocationDecision decision = bridge.allocate(request(SESSION, MANIFEST));

        assertEquals(SharedShardAllocationDecisionStatus.REJECTED, decision.status());
        assertEquals(
                Optional.of(SharedShardAllocationRejectionReason.INVALID_ALLOCATION_CLAIM),
                decision.receipt().rejectionReason());
    }

    private static SharedShardAllocationRequest request(
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId) {
        return new SharedShardAllocationRequest(EXPERIENCE, POOL, sessionId, resolvedManifestId, trace(), NOW);
    }

    private static HostAllocationClaim claim(
            SessionId sessionId,
            ResolvedManifestId resolvedManifestId,
            PoolId poolId,
            String instanceKind) {
        return new HostAllocationClaim(
                new SlotId("slot-lobby-1"),
                sessionId,
                new HostInstanceIdentity(
                        new InstanceId("instance-paper-lobby-1"),
                        instanceKind,
                        poolId,
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-paper-lobby-1")),
                resolvedManifestId,
                new HostNetworkEndpoint("paper-lobby.internal", 25_566),
                trace(),
                NOW);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-shared-shard-allocation",
                "span-shared-shard-allocation",
                Optional.empty(),
                NOW,
                "shared-shard-allocation-test",
                new InstanceId("instance-control-allocation"));
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
