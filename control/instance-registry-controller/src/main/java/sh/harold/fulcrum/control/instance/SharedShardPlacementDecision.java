package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.util.Objects;
import java.util.Optional;

public record SharedShardPlacementDecision(
        SharedShardPlacementDecisionStatus status,
        Optional<InstanceId> instanceId,
        Optional<SessionId> sessionId,
        Optional<SlotId> slotId,
        SharedShardPlacementRequest request) {
    public SharedShardPlacementDecision {
        status = Objects.requireNonNull(status, "status");
        instanceId = instanceId == null ? Optional.empty() : instanceId;
        sessionId = sessionId == null ? Optional.empty() : sessionId;
        slotId = slotId == null ? Optional.empty() : slotId;
        request = Objects.requireNonNull(request, "request");
        if (status == SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION
                && (instanceId.isEmpty() || sessionId.isEmpty() || slotId.isEmpty())) {
            throw new IllegalArgumentException("selected shared-shard placement requires Instance, Session, and Slot");
        }
        if (status == SharedShardPlacementDecisionStatus.REQUEST_ALLOCATION
                && (instanceId.isPresent() || sessionId.isPresent() || slotId.isPresent())) {
            throw new IllegalArgumentException("allocation request must not carry existing Instance, Session, or Slot");
        }
    }

    static SharedShardPlacementDecision selected(
            SharedShardPlacementRequest request,
            SharedShardPlacementCandidate candidate) {
        return new SharedShardPlacementDecision(
                SharedShardPlacementDecisionStatus.SELECTED_EXISTING_SESSION,
                Optional.of(candidate.instanceSnapshot().instanceId()),
                Optional.of(candidate.sessionId()),
                Optional.of(candidate.slotId()),
                request);
    }

    static SharedShardPlacementDecision requestAllocation(SharedShardPlacementRequest request) {
        return new SharedShardPlacementDecision(
                SharedShardPlacementDecisionStatus.REQUEST_ALLOCATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                request);
    }
}
