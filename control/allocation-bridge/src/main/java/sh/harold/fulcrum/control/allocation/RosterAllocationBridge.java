package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.control.queue.RosterIntentId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.api.HostAllocationRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RosterAllocationBridge {
    private final HostAllocationPort allocationPort;
    private final Map<RosterIntentId, StoredRosterAllocationDecision> acceptedLedger = new HashMap<>();

    public RosterAllocationBridge(HostAllocationPort allocationPort) {
        this.allocationPort = Objects.requireNonNull(allocationPort, "allocationPort");
    }

    public synchronized RosterAllocationDecision allocate(RosterAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        RosterIntentId rosterIntentId = request.rosterIntent().rosterIntentId();
        StoredRosterAllocationDecision stored = acceptedLedger.get(rosterIntentId);
        if (stored != null) {
            if (stored.requestFingerprint().equals(request.fingerprint())) {
                return stored.decision().asReplay();
            }
            return RosterAllocationDecision.rejected(request, RosterAllocationRejectionReason.IDEMPOTENCY_CONFLICT);
        }

        HostAllocationClaim claim;
        try {
            claim = allocationPort.allocate(toHostRequest(request));
        } catch (IllegalStateException failure) {
            return RosterAllocationDecision.rejected(request, RosterAllocationRejectionReason.ALLOCATION_UNAVAILABLE);
        }
        if (!claim.sessionId().equals(request.sessionId())
                || !claim.resolvedManifestId().equals(request.resolvedManifestId())
                || !claim.instanceIdentity().poolId().equals(request.rosterIntent().poolId())) {
            return RosterAllocationDecision.rejected(request, RosterAllocationRejectionReason.INVALID_ALLOCATION_CLAIM);
        }

        RosterAllocationReceipt receipt = RosterAllocationReceipt.accepted(request, claim);
        RosterAllocationDecision decision = RosterAllocationDecision.accepted(receipt, claim, emissions(request, claim, receipt));
        acceptedLedger.put(rosterIntentId, new StoredRosterAllocationDecision(request.fingerprint(), decision));
        return decision;
    }

    private static HostAllocationRequest toHostRequest(RosterAllocationRequest request) {
        return new HostAllocationRequest(
                request.rosterIntent().poolId(),
                request.sessionId(),
                request.resolvedManifestId(),
                request.rosterIntent().traceEnvelope(),
                request.requestedAt());
    }

    private static List<RosterAllocationEmission> emissions(
            RosterAllocationRequest request,
            HostAllocationClaim claim,
            RosterAllocationReceipt receipt) {
        return List.of(
                new RosterAllocationEmission(
                        RosterAllocationEmissionKind.HOST_ALLOCATION_REQUEST,
                        request.rosterIntent().rosterIntentId().value(),
                        "poolId=" + request.rosterIntent().poolId().value()
                                + "|sessionId=" + request.sessionId().value()
                                + "|resolvedManifestId=" + request.resolvedManifestId().value()
                                + "|traceId=" + request.rosterIntent().traceEnvelope().traceId()),
                new RosterAllocationEmission(
                        RosterAllocationEmissionKind.HOST_ALLOCATION_CLAIM,
                        claim.slotId().value(),
                        "rosterIntentId=" + request.rosterIntent().rosterIntentId().value()
                                + "|sessionId=" + claim.sessionId().value()
                                + "|instanceId=" + claim.instanceIdentity().instanceId().value()
                                + "|poolId=" + claim.instanceIdentity().poolId().value()
                                + "|minecraftHost=" + claim.minecraftEndpoint().host()
                                + "|minecraftPort=" + claim.minecraftEndpoint().port()
                                + "|traceId=" + claim.traceEnvelope().traceId()),
                new RosterAllocationEmission(
                        RosterAllocationEmissionKind.RESPONSE,
                        request.rosterIntent().rosterIntentId().value(),
                        receipt.wireValue()));
    }
}

record StoredRosterAllocationDecision(String requestFingerprint, RosterAllocationDecision decision) {
    StoredRosterAllocationDecision {
        requestFingerprint = ControlAllocationStrings.requireNonBlank(requestFingerprint, "requestFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
