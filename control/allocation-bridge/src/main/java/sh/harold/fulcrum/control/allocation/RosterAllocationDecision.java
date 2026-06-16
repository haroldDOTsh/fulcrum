package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.host.api.HostAllocationClaim;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RosterAllocationDecision(
        RosterAllocationDecisionStatus status,
        RosterAllocationReceipt receipt,
        Optional<HostAllocationClaim> claim,
        List<RosterAllocationEmission> emissions) {
    public RosterAllocationDecision {
        status = Objects.requireNonNull(status, "status");
        receipt = Objects.requireNonNull(receipt, "receipt");
        claim = claim == null ? Optional.empty() : claim;
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
    }

    public static RosterAllocationDecision accepted(
            RosterAllocationReceipt receipt,
            HostAllocationClaim claim,
            List<RosterAllocationEmission> emissions) {
        return new RosterAllocationDecision(RosterAllocationDecisionStatus.ACCEPTED, receipt, Optional.of(claim), emissions);
    }

    public static RosterAllocationDecision rejected(
            RosterAllocationRequest request,
            RosterAllocationRejectionReason reason) {
        RosterAllocationReceipt receipt = RosterAllocationReceipt.rejected(request, reason);
        return new RosterAllocationDecision(
                RosterAllocationDecisionStatus.REJECTED,
                receipt,
                Optional.empty(),
                List.of(new RosterAllocationEmission(
                        RosterAllocationEmissionKind.RESPONSE,
                        request.rosterIntent().rosterIntentId().value(),
                        receipt.wireValue())));
    }

    public RosterAllocationDecision asReplay() {
        return new RosterAllocationDecision(RosterAllocationDecisionStatus.REPLAYED, receipt, claim, List.of());
    }
}
