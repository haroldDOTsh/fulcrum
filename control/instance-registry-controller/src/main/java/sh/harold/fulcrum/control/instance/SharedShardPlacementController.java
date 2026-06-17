package sh.harold.fulcrum.control.instance;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SharedShardPlacementController {
    public SharedShardPlacementDecision place(
            SharedShardPlacementRequest request,
            List<SharedShardPlacementCandidate> candidates) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");

        return candidates.stream()
                .filter(candidate -> eligible(request, candidate))
                .max(Comparator
                        .comparingInt(SharedShardPlacementCandidate::currentOccupancy)
                        .thenComparing(candidate -> candidate.instanceSnapshot().instanceId().value()))
                .map(candidate -> SharedShardPlacementDecision.selected(request, candidate))
                .orElseGet(() -> SharedShardPlacementDecision.requestAllocation(request));
    }

    private static boolean eligible(
            SharedShardPlacementRequest request,
            SharedShardPlacementCandidate candidate) {
        InstanceSnapshot snapshot = candidate.instanceSnapshot();
        return snapshot.status() == InstanceRegistryStatus.READY
                && snapshot.poolId().equals(request.experience().poolId())
                && snapshot.resolvedManifestId().filter(request.experience().resolvedManifestId()::equals).isPresent()
                && candidate.hasCapacityFor(request);
    }
}
