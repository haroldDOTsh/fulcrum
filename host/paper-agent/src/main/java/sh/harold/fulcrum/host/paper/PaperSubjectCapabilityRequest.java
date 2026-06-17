package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;

public record PaperSubjectCapabilityRequest(
        SubjectId subjectId,
        String username) {
    public PaperSubjectCapabilityRequest {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        username = PaperArtifactNames.requireNonBlank(username, "username");
    }
}
