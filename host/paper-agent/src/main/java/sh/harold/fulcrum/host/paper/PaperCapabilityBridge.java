package sh.harold.fulcrum.host.paper;

public interface PaperCapabilityBridge {
    PaperSubjectCapabilityView subjectView(PaperSubjectCapabilityRequest request);

    PaperChatDecorationResponse decorateChat(PaperChatDecorationRequest request);
}
