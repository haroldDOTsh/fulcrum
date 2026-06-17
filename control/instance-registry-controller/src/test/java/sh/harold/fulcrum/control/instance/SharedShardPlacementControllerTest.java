package sh.harold.fulcrum.control.instance;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SharedShardPlacementControllerTest {
    private static final Instant NOW = Instant.parse("2026-06-17T08:00:00Z");
    private static final ExperienceId EXPERIENCE = new ExperienceId("experience-lobby");
    private static final PoolId POOL = new PoolId("pool-lobby");
    private static final PoolId OTHER_POOL = new PoolId("pool-other");
    private static final ResolvedManifestId MANIFEST = new ResolvedManifestId("manifest-lobby-bedrock");
    private static final ResolvedManifestId OTHER_MANIFEST = new ResolvedManifestId("manifest-other");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final PresenceId PRESENCE = new PresenceId("presence-lobby-1");
    private static final PrincipalId PRINCIPAL = new PrincipalId("control-placement");

    @Test
    void selectsReadySharedShardWithCapacity() {
        SharedShardPlacementDecision decision = new SharedShardPlacementController().place(
                request(100),
                List.of(candidate("paper-a", "session-lobby-a", POOL, MANIFEST, InstanceRegistryStatus.READY, 42, 100)));

        assertEquals(SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION, decision.status());
        assertEquals(Optional.of(new InstanceId("paper-a")), decision.instanceId());
        assertEquals(Optional.of(new SessionId("session-lobby-a")), decision.sessionId());
        assertEquals(Optional.of(new SlotId("slot-session-lobby-a")), decision.slotId());
    }

    @Test
    void secondSubjectReusesMostOccupiedEligibleSharedShardBeforeAllocating() {
        SharedShardPlacementDecision decision = new SharedShardPlacementController().place(
                request(100),
                List.of(
                        candidate("paper-a", "session-lobby-a", POOL, MANIFEST, InstanceRegistryStatus.READY, 10, 100),
                        candidate("paper-b", "session-lobby-b", POOL, MANIFEST, InstanceRegistryStatus.READY, 75, 100)));

        assertEquals(SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION, decision.status());
        assertEquals(Optional.of(new InstanceId("paper-b")), decision.instanceId());
        assertEquals(Optional.of(new SessionId("session-lobby-b")), decision.sessionId());
        assertEquals(Optional.of(new SlotId("slot-session-lobby-b")), decision.slotId());
    }

    @Test
    void requestsAllocationWhenEveryMatchingSharedShardIsFull() {
        SharedShardPlacementDecision decision = new SharedShardPlacementController().place(
                request(100),
                List.of(candidate("paper-a", "session-lobby-a", POOL, MANIFEST, InstanceRegistryStatus.READY, 100, 100)));

        assertEquals(SharedShardPlacementDecisionStatus.REQUEST_ALLOCATION, decision.status());
        assertEquals(Optional.empty(), decision.instanceId());
        assertEquals(Optional.empty(), decision.sessionId());
        assertEquals(Optional.empty(), decision.slotId());
    }

    @Test
    void requestsAllocationWhenMatchingShardStopsAcceptingPresences() {
        SharedShardPlacementDecision decision = new SharedShardPlacementController().place(
                request(100),
                List.of(candidate(
                        "paper-a",
                        "session-lobby-a",
                        POOL,
                        MANIFEST,
                        InstanceRegistryStatus.READY,
                        42,
                        100,
                        false)));

        assertEquals(SharedShardPlacementDecisionStatus.REQUEST_ALLOCATION, decision.status());
    }

    @Test
    void ignoresWrongPoolManifestAndNonReadyInstances() {
        SharedShardPlacementDecision decision = new SharedShardPlacementController().place(
                request(100),
                List.of(
                        candidate("paper-a", "session-lobby-a", OTHER_POOL, MANIFEST, InstanceRegistryStatus.READY, 10, 100),
                        candidate("paper-b", "session-lobby-b", POOL, OTHER_MANIFEST, InstanceRegistryStatus.READY, 10, 100),
                        candidate("paper-c", "session-lobby-c", POOL, MANIFEST, InstanceRegistryStatus.DRAINING, 10, 100)));

        assertEquals(SharedShardPlacementDecisionStatus.REQUEST_ALLOCATION, decision.status());
    }

    @Test
    void rejectsInvalidCapacityFacts() {
        assertThrows(IllegalArgumentException.class,
                () -> new SharedShardPlacementCandidate(
                        snapshot("paper-a", POOL, MANIFEST, InstanceRegistryStatus.READY),
                        occupancy("session-lobby-a", 101, 100, true)));
        assertThrows(IllegalArgumentException.class, () -> request(0));
    }

    private static SharedShardPlacementRequest request(int hardCapacity) {
        return new SharedShardPlacementRequest(
                new SharedShardExperienceDescriptor(
                        EXPERIENCE,
                        ExperienceShape.SHARED_SHARD,
                        new SharedShardPoolDescriptor(POOL, "lobby-fleet", Math.max(1, hardCapacity / 2), hardCapacity),
                        MANIFEST),
                SUBJECT,
                PRESENCE,
                "placement-attempt-lobby-1",
                Optional.of("capability-scope-lobby"),
                NOW,
                trace());
    }

    private static SharedShardPlacementCandidate candidate(
            String instanceId,
            String sessionId,
            PoolId poolId,
            ResolvedManifestId manifestId,
            InstanceRegistryStatus status,
            int occupancy,
            int hardCapacity) {
        return candidate(instanceId, sessionId, poolId, manifestId, status, occupancy, hardCapacity, true);
    }

    private static SharedShardPlacementCandidate candidate(
            String instanceId,
            String sessionId,
            PoolId poolId,
            ResolvedManifestId manifestId,
            InstanceRegistryStatus status,
            int occupancy,
            int hardCapacity,
            boolean acceptingPresences) {
        return new SharedShardPlacementCandidate(
                snapshot(instanceId, poolId, manifestId, status),
                occupancy(sessionId, occupancy, hardCapacity, acceptingPresences));
    }

    private static SharedShardOccupancySnapshot occupancy(
            String sessionId,
            int occupancy,
            int hardCapacity,
            boolean acceptingPresences) {
        return new SharedShardOccupancySnapshot(
                new SessionId(sessionId),
                new SlotId("slot-" + sessionId),
                occupancy,
                hardCapacity,
                acceptingPresences,
                NOW,
                trace());
    }

    private static InstanceSnapshot snapshot(
            String instanceId,
            PoolId poolId,
            ResolvedManifestId manifestId,
            InstanceRegistryStatus status) {
        return new InstanceSnapshot(
                new InstanceId(instanceId),
                "paper",
                poolId,
                new MachineRef("machine-a"),
                PRINCIPAL,
                Optional.of(manifestId),
                status,
                Optional.empty(),
                trace(),
                NOW);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-shared-shard-placement",
                "span-shared-shard-placement",
                Optional.empty(),
                NOW,
                "shared-shard-placement-test",
                new InstanceId("instance-placement-test"));
    }
}
