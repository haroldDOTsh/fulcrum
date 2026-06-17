package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;

public record PaperChatDecorationResponse(
        SubjectId subjectId,
        String decoratedMessage) {
    public PaperChatDecorationResponse {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        decoratedMessage = PaperArtifactNames.requireNonBlank(decoratedMessage, "decoratedMessage");
    }
}
