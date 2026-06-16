package sh.harold.fulcrum.api.kernel;

import java.util.UUID;

public record SubjectId(UUID value) {
    public SubjectId {
        value = Ids.requireUuid(value, "subjectId");
    }
}
