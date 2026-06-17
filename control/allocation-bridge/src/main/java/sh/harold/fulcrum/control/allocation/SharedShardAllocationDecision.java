package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.host.api.HostAllocationClaim;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SharedShardAllocationDecision(
        SharedShardAllocationDecisionStatus status,
        SharedShardAllocationReceipt receipt,
        Optional<HostAllocationClaim> claim,
        List<SharedShardAllocationEmission> emissions) {
    public SharedShardAllocationDecision {
        status = Objects.requireNonNull(status, "status");
        receipt = Objects.requireNonNull(receipt, "receipt");
        claim = claim == null ? Optional.empty() : claim;
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
    }

    public static SharedShardAllocationDecision accepted(
            SharedShardAllocationReceipt receipt,
            HostAllocationClaim claim,
            List<SharedShardAllocationEmission> emissions) {
        return new SharedShardAllocationDecision(
                SharedShardAllocationDecisionStatus.ACCEPTED,
                receipt,
                Optional.of(claim),
                emissions);
    }

    public static SharedShardAllocationDecision rejected(
            SharedShardAllocationRequest request,
            SharedShardAllocationRejectionReason reason) {
        SharedShardAllocationReceipt receipt = SharedShardAllocationReceipt.rejected(request, reason);
        return new SharedShardAllocationDecision(
                SharedShardAllocationDecisionStatus.REJECTED,
                receipt,
                Optional.empty(),
                List.of(new SharedShardAllocationEmission(
                        SharedShardAllocationEmissionKind.RESPONSE,
                        request.sessionId().value(),
                        receipt.wireValue())));
    }

    public SharedShardAllocationDecision asReplay() {
        return new SharedShardAllocationDecision(SharedShardAllocationDecisionStatus.REPLAYED, receipt, claim, List.of());
    }
}
