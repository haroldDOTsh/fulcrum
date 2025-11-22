package sh.harold.fulcrum.api.chat;

import net.kyori.adventure.text.Component;

import java.util.regex.Pattern;

/**
 * Canonical emoji tokens that can be used in chat.
 * Add additional enum constants to expose new emojis.
 */
public enum ChatEmoji {
    HEART(":heart:", "❤", ChatEmojiPack.CORE),
    FIRE(":fire:", "🔥", ChatEmojiPack.CORE),
    STAR(":star:", "⭐", ChatEmojiPack.CORE),
    SMILE(":smile:", "😊", ChatEmojiPack.CORE),
    LAUGH(":laugh:", "😄", ChatEmojiPack.CORE),

    PARTY_POPPER(":party:", "🎉", ChatEmojiPack.CELEBRATION),
    CONFETTI(":confetti:", "🎊", ChatEmojiPack.CELEBRATION),

    STAFF_SHIELD(":staff:", "🛡️", ChatEmojiPack.STAFF);

    private final String token;
    private final Component component;
    private final Pattern pattern;
    private final ChatEmojiPack pack;

    ChatEmoji(String token, String unicodeGlyph, ChatEmojiPack pack) {
        this.token = token;
        this.component = Component.text(unicodeGlyph);
        this.pattern = Pattern.compile("(?i)" + Pattern.quote(token));
        this.pack = pack;
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

    public ChatEmojiPack pack() {
        return pack;
    }
}
