package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;

import java.time.Instant;
import java.util.Objects;

public record PlaceExperienceSession(
        SessionId sessionId,
        SlotId allocationSlotId,
        InstanceId instanceId,
        ResolvedManifestId resolvedManifestId,
        Instant placedAt,
        TraceEnvelope traceEnvelope) implements ExperienceSessionCommand {
    public PlaceExperienceSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        allocationSlotId = Objects.requireNonNull(allocationSlotId, "allocationSlotId");
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        placedAt = Objects.requireNonNull(placedAt, "placedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
