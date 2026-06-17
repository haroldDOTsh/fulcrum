package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;

public record PaperChatDecorationRequest(
        SubjectId subjectId,
        String username,
        String message) {
    public PaperChatDecorationRequest {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        username = PaperArtifactNames.requireNonBlank(username, "username");
        message = PaperArtifactNames.requireNonBlank(message, "message");
    }
}
