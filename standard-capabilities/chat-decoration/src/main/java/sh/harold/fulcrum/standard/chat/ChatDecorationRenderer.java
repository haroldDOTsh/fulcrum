package sh.harold.fulcrum.standard.chat;

import java.util.Objects;

public final class ChatDecorationRenderer {
    private ChatDecorationRenderer() {
    }

    public static ChatDecorationResult decorate(ChatDecorationInput input) {
        ChatDecorationInput checkedInput = Objects.requireNonNull(input, "input");
        String decoratedName = checkedInput.rankLabel()
                .map(rankLabel -> "[" + rankLabel + "] " + checkedInput.displayName())
                .orElse(checkedInput.displayName());
        return new ChatDecorationResult(
                checkedInput.subjectId(),
                decoratedName + ": " + checkedInput.message());
    }
}
