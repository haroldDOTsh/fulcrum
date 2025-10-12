package sh.harold.fulcrum.api.menu.util;

import org.bukkit.ChatColor;

/**
 * Utility class for color conversion operations.
 * Centralizes color handling logic to avoid code duplication.
 */
public final class ColorUtils {

    private ColorUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Converts a string representation of a color to a ChatColor.
     * Supports both color names (e.g., "RED", "BLUE") and color codes (e.g., "&amp;c", "&amp;9").
     *
     * @param color The color string to convert
     * @return The corresponding ChatColor, or null if the color is invalid
     */
    public static ChatColor convertColor(String color) {
        if (color == null || color.isEmpty()) {
            return null;
        }

        // Try to parse as ChatColor enum value
        try {
            return ChatColor.valueOf(color.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            // Not a valid enum value, try color code
        }

        // Try to parse as color code (e.g., "&c" or just "c")
        if (color.length() == 2 && color.charAt(0) == '&') {
            return ChatColor.getByChar(color.charAt(1));
        } else if (color.length() == 1) {
            return ChatColor.getByChar(color.charAt(0));
        }

        // Hex color support removed as ChatColor.of() is not available in all Bukkit versions
        // Could be added back with version detection or a different approach

        return null;
    }

    /**
     * Converts legacy color codes (&amp;) to MiniMessage format.
     * This is used to convert legacy Bukkit color codes to the modern MiniMessage format.
     *
     * @param text The text with legacy color codes
     * @return The text with MiniMessage format codes
     */
    public static String convertLegacyToMiniMessage(String text) {
        if (text == null) return null;

        // Replace color codes
        text = text.replace("&0", "<black>");
        text = text.replace("&1", "<dark_blue>");
        text = text.replace("&2", "<dark_green>");
        text = text.replace("&3", "<dark_aqua>");
        text = text.replace("&4", "<dark_red>");
        text = text.replace("&5", "<dark_purple>");
        text = text.replace("&6", "<gold>");
        text = text.replace("&7", "<gray>");
        text = text.replace("&8", "<dark_gray>");
        text = text.replace("&9", "<blue>");
        text = text.replace("&a", "<green>");
        text = text.replace("&b", "<aqua>");
        text = text.replace("&c", "<red>");
        text = text.replace("&d", "<light_purple>");
        text = text.replace("&e", "<yellow>");
        text = text.replace("&f", "<white>");

        // Replace formatting codes
        text = text.replace("&k", "<obfuscated>");
        text = text.replace("&l", "<bold>");
        text = text.replace("&m", "<strikethrough>");
        text = text.replace("&n", "<underlined>");
        text = text.replace("&o", "<italic>");
        text = text.replace("&r", "<reset>");

        return text;
    }

    /**
     * Applies color codes to a string.
     * Replaces &amp; with the section symbol for color codes.
     *
     * @param text The text to colorize
     * @return The colorized text
     */
    public static String colorize(String text) {
        if (text == null) {
            return null;
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Strips all color codes from a string.
     *
     * @param text The text to strip colors from
     * @return The text without color codes
     */
    public static String stripColors(String text) {
        if (text == null) {
            return null;
        }
        return ChatColor.stripColor(text);
    }
}