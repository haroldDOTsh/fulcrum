package sh.harold.fulcrum.standard.chat;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Objects;

public record ChatDecorationResult(SubjectId subjectId, String renderedText) {
    public ChatDecorationResult {
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        renderedText = requireNonBlank(renderedText, "renderedText");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
