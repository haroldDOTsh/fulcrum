package sh.harold.fulcrum.api.chat;

import net.kyori.adventure.text.Component;

import java.util.regex.Pattern;

/**
 * Canonical emoji tokens that can be used in chat.
 * Add additional enum constants to expose new emojis.
 */
public enum ChatEmoji {
    HEART(":heart:", "❤️"),
    FIRE(":fire:", "🔥"),
    STAR(":star:", "⭐"),
    SMILE(":smile:", "😊"),
    PARTY_POPPER(":party:", "🎉");

    private final String token;
    private final Component component;
    private final Pattern pattern;

    ChatEmoji(String token, String unicodeGlyph) {
        this.token = token;
        this.component = Component.text(unicodeGlyph);
        this.pattern = Pattern.compile("(?i)" + Pattern.quote(token));
    }

    public String token() {
        return token;
    }

    public Component component() {
        return component;
    }

    public Pattern pattern() {
        return pattern;
    }
}
