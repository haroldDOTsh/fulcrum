package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;

import java.util.Objects;
import java.util.Optional;

public record SharedShardAllocationReceipt(
        boolean accepted,
        SessionId sessionId,
        Optional<HostAllocationClaim> claim,
        Optional<SharedShardAllocationRejectionReason> rejectionReason) {
    public SharedShardAllocationReceipt {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        claim = claim == null ? Optional.empty() : claim;
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
    }

    public static SharedShardAllocationReceipt accepted(
            SharedShardAllocationRequest request,
            HostAllocationClaim claim) {
        return new SharedShardAllocationReceipt(true, request.sessionId(), Optional.of(claim), Optional.empty());
    }

    public static SharedShardAllocationReceipt rejected(
            SharedShardAllocationRequest request,
            SharedShardAllocationRejectionReason reason) {
        return new SharedShardAllocationReceipt(false, request.sessionId(), Optional.empty(), Optional.of(reason));
    }

    public String wireValue() {
        return "accepted=" + accepted
                + "|sessionId=" + sessionId.value()
                + "|slotId=" + claim.map(value -> value.slotId().value()).orElse("none")
                + "|minecraftHost=" + claim.map(value -> value.minecraftEndpoint().host()).orElse("none")
                + "|minecraftPort=" + claim.map(value -> Integer.toString(value.minecraftEndpoint().port())).orElse("none")
                + "|reason=" + rejectionReason.map(Enum::name).orElse("none");
    }
}
