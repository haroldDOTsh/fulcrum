package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record PunishmentLoginRequest(SubjectId subjectId, Instant attemptedAt) {
    public PunishmentLoginRequest {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
    }
}
