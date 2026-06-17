package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceKinds;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SharedShardAllocationBridge {
    private final HostAllocationPort allocationPort;
    private final Map<SessionId, StoredSharedShardAllocationDecision> acceptedLedger = new HashMap<>();

    public SharedShardAllocationBridge(HostAllocationPort allocationPort) {
        this.allocationPort = Objects.requireNonNull(allocationPort, "allocationPort");
    }

    public synchronized SharedShardAllocationDecision allocate(SharedShardAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        StoredSharedShardAllocationDecision stored = acceptedLedger.get(request.sessionId());
        if (stored != null) {
            if (stored.requestFingerprint().equals(request.fingerprint())) {
                return stored.decision().asReplay();
            }
            return SharedShardAllocationDecision.rejected(
                    request,
                    SharedShardAllocationRejectionReason.IDEMPOTENCY_CONFLICT);
        }

        HostAllocationClaim claim;
        try {
            claim = allocationPort.allocate(toHostRequest(request));
        } catch (IllegalStateException failure) {
            return SharedShardAllocationDecision.rejected(
                    request,
                    SharedShardAllocationRejectionReason.ALLOCATION_UNAVAILABLE);
        }
        if (!validClaim(request, claim)) {
            return SharedShardAllocationDecision.rejected(
                    request,
                    SharedShardAllocationRejectionReason.INVALID_ALLOCATION_CLAIM);
        }

        SharedShardAllocationReceipt receipt = SharedShardAllocationReceipt.accepted(request, claim);
        SharedShardAllocationDecision decision =
                SharedShardAllocationDecision.accepted(receipt, claim, emissions(request, claim, receipt));
        acceptedLedger.put(request.sessionId(), new StoredSharedShardAllocationDecision(request.fingerprint(), decision));
        return decision;
    }

    private static HostAllocationRequest toHostRequest(SharedShardAllocationRequest request) {
        return new HostAllocationRequest(
                request.poolId(),
                request.sessionId(),
                request.resolvedManifestId(),
                request.traceEnvelope(),
                request.requestedAt());
    }

    private static boolean validClaim(SharedShardAllocationRequest request, HostAllocationClaim claim) {
        return claim.sessionId().equals(request.sessionId())
                && claim.resolvedManifestId().equals(request.resolvedManifestId())
                && claim.instanceIdentity().poolId().equals(request.poolId())
                && HostInstanceKinds.PAPER.equals(claim.instanceIdentity().instanceKind());
    }

    private static List<SharedShardAllocationEmission> emissions(
            SharedShardAllocationRequest request,
            HostAllocationClaim claim,
            SharedShardAllocationReceipt receipt) {
        return List.of(
                new SharedShardAllocationEmission(
                        SharedShardAllocationEmissionKind.HOST_ALLOCATION_REQUEST,
                        request.sessionId().value(),
                        "experienceId=" + request.experienceId().value()
                                + "|poolId=" + request.poolId().value()
                                + "|sessionId=" + request.sessionId().value()
                                + "|resolvedManifestId=" + request.resolvedManifestId().value()
                                + "|traceId=" + request.traceEnvelope().traceId()),
                new SharedShardAllocationEmission(
                        SharedShardAllocationEmissionKind.HOST_ALLOCATION_CLAIM,
                        claim.slotId().value(),
                        "experienceId=" + request.experienceId().value()
                                + "|sessionId=" + claim.sessionId().value()
                                + "|instanceId=" + claim.instanceIdentity().instanceId().value()
                                + "|poolId=" + claim.instanceIdentity().poolId().value()
                                + "|minecraftHost=" + claim.minecraftEndpoint().host()
                                + "|minecraftPort=" + claim.minecraftEndpoint().port()
                                + "|traceId=" + claim.traceEnvelope().traceId()),
                new SharedShardAllocationEmission(
                        SharedShardAllocationEmissionKind.RESPONSE,
                        request.sessionId().value(),
                        receipt.wireValue()));
    }
}

record StoredSharedShardAllocationDecision(String requestFingerprint, SharedShardAllocationDecision decision) {
    StoredSharedShardAllocationDecision {
        requestFingerprint = ControlAllocationStrings.requireNonBlank(requestFingerprint, "requestFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
