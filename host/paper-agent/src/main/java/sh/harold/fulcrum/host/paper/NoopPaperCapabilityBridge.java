package sh.harold.fulcrum.host.paper;

import java.util.Objects;

public final class NoopPaperCapabilityBridge implements PaperCapabilityBridge {
    @Override
    public PaperSubjectCapabilityView subjectView(PaperSubjectCapabilityRequest request) {
        PaperSubjectCapabilityRequest checkedRequest = Objects.requireNonNull(request, "request");
        return PaperSubjectCapabilityView.fallback(checkedRequest.subjectId(), checkedRequest.username());
    }

    @Override
    public PaperChatDecorationResponse decorateChat(PaperChatDecorationRequest request) {
        PaperChatDecorationRequest checkedRequest = Objects.requireNonNull(request, "request");
        return new PaperChatDecorationResponse(
                checkedRequest.subjectId(),
                checkedRequest.username() + ": " + checkedRequest.message());
    }
}
