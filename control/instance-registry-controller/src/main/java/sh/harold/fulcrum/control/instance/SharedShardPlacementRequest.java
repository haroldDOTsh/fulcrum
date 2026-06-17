package sh.harold.fulcrum.control.instance;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SharedShardPlacementRequest(
        SharedShardExperienceDescriptor experience,
        SubjectId subjectId,
        PresenceId presenceId,
        String placementAttemptId,
        Optional<String> capabilityScopeFingerprint,
        Instant requestedAt,
        TraceEnvelope traceEnvelope) {
    public SharedShardPlacementRequest {
        experience = Objects.requireNonNull(experience, "experience");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        presenceId = Objects.requireNonNull(presenceId, "presenceId");
        placementAttemptId = ControlInstanceStrings.requireNonBlank(placementAttemptId, "placementAttemptId");
        capabilityScopeFingerprint = capabilityScopeFingerprint == null
                ? Optional.empty()
                : capabilityScopeFingerprint.map(value ->
                        ControlInstanceStrings.requireNonBlank(value, "capabilityScopeFingerprint"));
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
