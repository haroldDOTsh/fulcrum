package sh.harold.fulcrum.message.payload;

import java.util.Locale;
import java.util.Objects;

/**
 * Represents the identifier provided when constructing a message.
 * Supports both translation keys (feature.path) and literal strings when translation
 * is skipped.
 */
public final class MessagePayload {
    private static final String DEFAULT_FEATURE = "general";

    private final String raw;
    private final String feature;
    private final String path;

    private MessagePayload(String raw, String feature, String path) {
        this.raw = raw;
        this.feature = feature;
        this.path = path;
    }

    public static MessagePayload of(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Message identifier cannot be blank");
        }
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return new MessagePayload(trimmed, DEFAULT_FEATURE, trimmed);
        }
        String feature = trimmed.substring(0, dot);
        String path = trimmed.substring(dot + 1);
        return new MessagePayload(trimmed, feature, path);
    }

    public String raw() {
        return raw;
    }

    public String feature() {
        return feature;
    }

    public String path() {
        return path;
    }

    public String bundleKey(Locale locale) {
        return feature + ":" + locale.toLanguageTag();
    }
}
