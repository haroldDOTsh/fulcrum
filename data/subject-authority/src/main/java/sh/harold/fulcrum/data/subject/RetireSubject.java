package sh.harold.fulcrum.data.subject;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record RetireSubject(
        SubjectId subjectId,
        Instant retiredAt,
        SubjectRetireReason reason) implements SubjectCommand {
    public RetireSubject {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        retiredAt = Objects.requireNonNull(retiredAt, "retiredAt");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
