package sh.harold.fulcrum.standard.chat;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChatDecorationRendererTest {
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000401"));

    @Test
    void rendersRankDecoratedChatText() {
        ChatDecorationResult result = ChatDecorationRenderer.decorate(new ChatDecorationInput(
                SUBJECT,
                "Harold",
                Optional.of("Admin"),
                "hello network"));

        assertEquals(SUBJECT, result.subjectId());
        assertEquals("[Admin] Harold: hello network", result.renderedText());
    }

    @Test
    void fallsBackToDisplayNameWithoutRankLabel() {
        ChatDecorationResult result = ChatDecorationRenderer.decorate(new ChatDecorationInput(
                SUBJECT,
                "Harold",
                Optional.empty(),
                "hello network"));

        assertEquals("Harold: hello network", result.renderedText());
    }

    @Test
    void normalizesDisplayNameRankLabelAndMessageWhitespace() {
        ChatDecorationResult result = ChatDecorationRenderer.decorate(new ChatDecorationInput(
                SUBJECT,
                " Harold ",
                Optional.of(" Admin "),
                " hello network "));

        assertEquals("[Admin] Harold: hello network", result.renderedText());
    }
}
