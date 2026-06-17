package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PaperCapabilityBridgeServerTest {
    @Test
    void clientAndServerRoundTripSubjectViewAndChatDecoration() {
        SubjectId subjectId = new SubjectId(UUID.fromString("99999999-9999-9999-9999-999999999999"));
        PaperCapabilityBridge bridge = new PaperCapabilityBridge() {
            @Override
            public PaperSubjectCapabilityView subjectView(PaperSubjectCapabilityRequest request) {
                assertEquals(subjectId, request.subjectId());
                assertEquals("Richer=Toast\nOne", request.username());
                return new PaperSubjectCapabilityView(
                        request.subjectId(),
                        "Richer=Toast\nOne",
                        Optional.of("Admin=Owner"));
            }

            @Override
            public PaperChatDecorationResponse decorateChat(PaperChatDecorationRequest request) {
                assertEquals(subjectId, request.subjectId());
                assertEquals("Richer=Toast\nOne", request.username());
                assertEquals("hello=there\nnow", request.message());
                return new PaperChatDecorationResponse(
                        request.subjectId(),
                        "[Admin=Owner] Richer=Toast\nOne: hello=there\nnow");
            }
        };

        try (PaperCapabilityBridgeServer server = new PaperCapabilityBridgeServer(
                URI.create("http://127.0.0.1:0/capabilities"),
                bridge)) {
            server.start();
            PaperCapabilityBridgeClient client = new PaperCapabilityBridgeClient(server.uri());

            PaperSubjectCapabilityView view = client.subjectView(new PaperSubjectCapabilityRequest(
                    subjectId,
                    "Richer=Toast\nOne"));
            PaperChatDecorationResponse response = client.decorateChat(new PaperChatDecorationRequest(
                    subjectId,
                    "Richer=Toast\nOne",
                    "hello=there\nnow"));

            assertEquals("Richer=Toast\nOne", view.displayName());
            assertEquals("Admin=Owner", view.rankLabel().orElseThrow());
            assertEquals("[Admin=Owner] Richer=Toast\nOne", view.decoratedDisplayName());
            assertEquals("[Admin=Owner] Richer=Toast\nOne: hello=there\nnow", response.decoratedMessage());
        }
    }
}
