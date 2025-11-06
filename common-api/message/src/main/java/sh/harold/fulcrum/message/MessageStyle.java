package sh.harold.fulcrum.message;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Styles applied to rendered messages. Each style provides a base color for the message
 * and an optional highlight color for argument placeholders.
 */
public enum MessageStyle {
    SUCCESS(NamedTextColor.GREEN, NamedTextColor.YELLOW),
    INFO(NamedTextColor.GRAY, NamedTextColor.AQUA),
    DEBUG(NamedTextColor.GRAY, NamedTextColor.DARK_GRAY),
    ERROR(NamedTextColor.RED, NamedTextColor.RED);

    private final NamedTextColor bodyColor;
    private final NamedTextColor argumentColor;
    private final String prefixOpenTag;
    private final String argumentOpenTag;
    private final String argumentCloseTag;

    MessageStyle(NamedTextColor bodyColor, NamedTextColor argumentColor) {
        this.bodyColor = bodyColor;
        this.argumentColor = argumentColor;
        String bodyToken = colorToken(bodyColor);
        this.prefixOpenTag = "<" + bodyToken + ">";
        if (argumentColor != null) {
            String argToken = colorToken(argumentColor);
            this.argumentOpenTag = "<" + argToken + ">";
            this.argumentCloseTag = "</" + argToken + ">";
        } else {
            this.argumentOpenTag = "";
            this.argumentCloseTag = "";
        }
    }

    private static String colorToken(TextColor color) {
        if (color instanceof NamedTextColor namedColor) {
            String token = NamedTextColor.NAMES.key(namedColor);
            if (token != null) {
                return token;
            }
        }
        return color.asHexString();
    }

    public NamedTextColor bodyColor() {
        return bodyColor;
    }

    public boolean hasArgumentColor() {
        return !argumentOpenTag.isEmpty();
    }

    public String prefixOpenTag() {
        return prefixOpenTag;
    }

    public String argumentOpenTag() {
        return argumentOpenTag;
    }

    public String argumentCloseTag() {
        return argumentCloseTag;
    }
}
