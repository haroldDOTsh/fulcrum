package sh.harold.fulcrum.adapters.agones.fake;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostReadinessReport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FakeAgonesAllocationAdapter implements HostAllocationPort {
    private final Map<PoolId, Deque<HostReadinessReport>> readyInstances = new HashMap<>();
    private final Set<InstanceId> readyInstanceIds = new HashSet<>();
    private final Map<InstanceId, HostAllocationClaim> activeClaims = new HashMap<>();

    public synchronized void registerReadyPaperInstance(HostReadinessReport report) {
        if (!HostInstanceKinds.PAPER.equals(report.instanceIdentity().instanceKind())) {
            throw new IllegalArgumentException("Agones allocation in Step 2 only claims Ready Paper Instances");
        }
        InstanceId instanceId = report.instanceIdentity().instanceId();
        if (readyInstanceIds.contains(instanceId) || activeClaims.containsKey(instanceId)) {
            throw new IllegalStateException("Instance already registered or allocated: " + instanceId.value());
        }
        readyInstances.computeIfAbsent(report.instanceIdentity().poolId(), ignored -> new ArrayDeque<>()).addLast(report);
        readyInstanceIds.add(instanceId);
    }

    @Override
    public synchronized HostAllocationClaim allocate(HostAllocationRequest request) {
        Deque<HostReadinessReport> poolInstances = readyInstances.get(request.poolId());
        if (poolInstances == null || poolInstances.isEmpty()) {
            throw new IllegalStateException("No Ready Paper Instance available for pool: " + request.poolId().value());
        }
        HostReadinessReport report = poolInstances.removeFirst();
        InstanceId instanceId = report.instanceIdentity().instanceId();
        readyInstanceIds.remove(instanceId);
        HostAllocationClaim claim = new HostAllocationClaim(
                new SlotId("slot-" + instanceId.value()),
                request.sessionId(),
                report.instanceIdentity(),
                request.resolvedManifestId(),
                request.traceEnvelope(),
                request.requestedAt());
        activeClaims.put(instanceId, claim);
        return claim;
    }

    public synchronized Optional<HostAllocationClaim> activeClaim(InstanceId instanceId) {
        return Optional.ofNullable(activeClaims.get(instanceId));
    }
}
