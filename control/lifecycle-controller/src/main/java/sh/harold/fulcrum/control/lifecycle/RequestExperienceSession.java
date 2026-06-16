package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RequestExperienceSession(
        SessionId sessionId,
        ExperienceId experienceId,
        Optional<String> modeId,
        String sessionType,
        List<SubjectId> subjectIds,
        Instant requestedAt,
        TraceEnvelope traceEnvelope) implements ExperienceSessionCommand {
    public RequestExperienceSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        modeId = modeId == null
                ? Optional.empty()
                : modeId.map(value -> ControlLifecycleStrings.requireNonBlank(value, "modeId"));
        sessionType = ControlLifecycleStrings.requireNonBlank(sessionType, "sessionType");
        subjectIds = List.copyOf(Objects.requireNonNull(subjectIds, "subjectIds"));
        if (subjectIds.isEmpty()) {
            throw new IllegalArgumentException("subjectIds must not be empty");
        }
        if (new HashSet<>(subjectIds).size() != subjectIds.size()) {
            throw new IllegalArgumentException("subjectIds must not contain duplicates");
        }
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
    }
}
