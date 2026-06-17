package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;

import java.util.Objects;
import java.util.Optional;

public record RosterAllocationReceipt(
        boolean accepted,
        RosterIntentId rosterIntentId,
        Optional<HostAllocationClaim> claim,
        Optional<RosterAllocationRejectionReason> rejectionReason) {
    public RosterAllocationReceipt {
        rosterIntentId = Objects.requireNonNull(rosterIntentId, "rosterIntentId");
        claim = claim == null ? Optional.empty() : claim;
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
    }

    public static RosterAllocationReceipt accepted(RosterAllocationRequest request, HostAllocationClaim claim) {
        return new RosterAllocationReceipt(true, request.rosterIntent().rosterIntentId(), Optional.of(claim), Optional.empty());
    }

    public static RosterAllocationReceipt rejected(
            RosterAllocationRequest request,
            RosterAllocationRejectionReason reason) {
        return new RosterAllocationReceipt(false, request.rosterIntent().rosterIntentId(), Optional.empty(), Optional.of(reason));
    }

    public String wireValue() {
        return "accepted=" + accepted
                + "|rosterIntentId=" + rosterIntentId.value()
                + "|slotId=" + claim.map(value -> value.slotId().value()).orElse("none")
                + "|minecraftHost=" + claim.map(value -> value.minecraftEndpoint().host()).orElse("none")
                + "|minecraftPort=" + claim.map(value -> Integer.toString(value.minecraftEndpoint().port())).orElse("none")
                + "|reason=" + rejectionReason.map(Enum::name).orElse("none");
    }
}
