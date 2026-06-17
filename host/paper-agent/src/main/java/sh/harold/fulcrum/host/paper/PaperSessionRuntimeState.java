package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record PaperSessionRuntimeState(
        SessionId sessionId,
        Set<SubjectId> attachedSubjects) {
    public PaperSessionRuntimeState {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        attachedSubjects = Set.copyOf(Objects.requireNonNull(attachedSubjects, "attachedSubjects"));
    }

    public static PaperSessionRuntimeState empty(SessionId sessionId) {
        return new PaperSessionRuntimeState(sessionId, Set.of());
    }

    PaperSessionRuntimeState attach(SubjectId subjectId) {
        LinkedHashSet<SubjectId> next = new LinkedHashSet<>(attachedSubjects);
        next.add(Objects.requireNonNull(subjectId, "subjectId"));
        return new PaperSessionRuntimeState(sessionId, next);
    }

    PaperSessionRuntimeState detach(SubjectId subjectId) {
        LinkedHashSet<SubjectId> next = new LinkedHashSet<>(attachedSubjects);
        next.remove(Objects.requireNonNull(subjectId, "subjectId"));
        return new PaperSessionRuntimeState(sessionId, next);
    }
}
