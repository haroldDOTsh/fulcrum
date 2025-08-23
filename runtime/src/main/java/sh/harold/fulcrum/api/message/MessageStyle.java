package sh.harold.fulcrum.api.message;

import net.kyori.adventure.text.format.NamedTextColor;

public enum MessageStyle {
    SUCCESS(NamedTextColor.GREEN, NamedTextColor.YELLOW),
    INFO(NamedTextColor.GRAY, NamedTextColor.AQUA),
    DEBUG(NamedTextColor.DARK_GRAY, NamedTextColor.DARK_GRAY),
    ERROR(NamedTextColor.RED, NamedTextColor.RED),
    RAW(NamedTextColor.WHITE, null);

    private final NamedTextColor color;
    private final NamedTextColor argumentColor;

    MessageStyle(NamedTextColor color, NamedTextColor argumentColor) {
        this.color = color;
        this.argumentColor = argumentColor;
    }

    public String getPrefix() {
        return "<" + color.toString() + ">";
    }

    public String getArgumentColorTag() {
        return argumentColor != null ? "<" + argumentColor.toString() + ">" : "";
    }
}