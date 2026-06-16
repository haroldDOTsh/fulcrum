package sh.harold.fulcrum.standard.chat;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;
import java.util.Optional;

public record ChatDecorationInput(
        SubjectId subjectId,
        String displayName,
        Optional<String> rankLabel,
        String message) {
    public ChatDecorationInput {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        displayName = requireNonBlank(displayName, "displayName");
        rankLabel = normalizeOptional(rankLabel, "rankLabel");
        message = requireNonBlank(message, "message");
    }

    private static Optional<String> normalizeOptional(Optional<String> value, String label) {
        return Objects.requireNonNull(value, label)
                .map(String::trim)
                .filter(text -> !text.isEmpty());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
