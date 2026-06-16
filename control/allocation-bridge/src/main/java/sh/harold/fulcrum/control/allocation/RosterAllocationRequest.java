package sh.harold.fulcrum.control.allocation;

import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.control.queue.RosterIntentSnapshot;

import java.time.Instant;
import java.util.Objects;

public record RosterAllocationRequest(
        RosterIntentSnapshot rosterIntent,
        SessionId sessionId,
        ResolvedManifestId resolvedManifestId,
        Instant requestedAt) {
    public RosterAllocationRequest {
        rosterIntent = Objects.requireNonNull(rosterIntent, "rosterIntent");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        if (requestedAt.isBefore(rosterIntent.formedAt())) {
            throw new IllegalArgumentException("requestedAt must not be before roster formation");
        }
    }

    String fingerprint() {
        return rosterIntent.rosterIntentId().value()
                + "|" + sessionId.value()
                + "|" + resolvedManifestId.value();
    }
}
