package sh.harold.fulcrum.api.message.scoreboard.util;

import java.util.regex.Pattern;

/**
 * Utility class for processing color codes in scoreboard content.
 * This class handles the conversion of color codes from various formats
 * to the appropriate display format, including legacy color codes and MiniMessage.
 *
 * <p>The processor supports:
 * <ul>
 *   <li>Legacy color codes (&amp;a, &amp;l, etc.)</li>
 *   <li>Section symbol color codes (§a, §l, etc.)</li>
 *   <li>MiniMessage format (&lt;red&gt;, &lt;bold&gt;, etc.)</li>
 *   <li>Hex color codes (&amp;#FF0000, &lt;#FF0000&gt;)</li>
 * </ul>
 */
public class ColorCodeProcessor {

    // Legacy color code patterns
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("&([0-9a-fA-Fk-oK-OrR])");
    private static final Pattern SECTION_COLOR_PATTERN = Pattern.compile("§([0-9a-fA-Fk-oK-OrR])");

    // Hex color patterns
    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("&(#[0-9a-fA-F]{6})");
    private static final Pattern MINIMESSAGE_HEX_PATTERN = Pattern.compile("<(#[0-9a-fA-F]{6})>");

    // MiniMessage patterns
    private static final Pattern MINIMESSAGE_PATTERN = Pattern.compile("<(/?)([a-zA-Z0-9_]+)(?::([^>]+))?>");

    // Strip patterns
    private static final Pattern STRIP_LEGACY_PATTERN = Pattern.compile("&[0-9a-fA-Fk-oK-OrR]");
    private static final Pattern STRIP_SECTION_PATTERN = Pattern.compile("§[0-9a-fA-Fk-oK-OrR]");
    private static final Pattern STRIP_HEX_PATTERN = Pattern.compile("&(#[0-9a-fA-F]{6})");
    private static final Pattern STRIP_MINIMESSAGE_PATTERN = Pattern.compile("</?[^>]+>");

    /**
     * Processes legacy color codes (&amp;a, &amp;l, etc.) to section symbols.
     *
     * @param text the text to process
     * @return the text with legacy codes converted to section symbols
     * @throws IllegalArgumentException if text is null
     */
    public static String processLegacyCodes(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }
        return LEGACY_COLOR_PATTERN.matcher(text).replaceAll("§$1");
    }

    /**
     * Processes section symbol color codes to legacy format.
     *
     * @param text the text to process
     * @return the text with section symbols converted to legacy codes
     * @throws IllegalArgumentException if text is null
     */
    public static String processToLegacy(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }
        return SECTION_COLOR_PATTERN.matcher(text).replaceAll("&$1");
    }

    /**
     * Processes hex color codes to the appropriate format.
     *
     * @param text          the text to process
     * @param toMiniMessage whether to convert to MiniMessage format
     * @return the text with hex codes processed
     * @throws IllegalArgumentException if text is null
     */
    public static String processHexCodes(String text, boolean toMiniMessage) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }

        if (toMiniMessage) {
            // Convert &#RRGGBB to <#RRGGBB>
            text = LEGACY_HEX_PATTERN.matcher(text).replaceAll("<$1>");
        } else {
            // Convert <#RRGGBB> to &#RRGGBB
            text = MINIMESSAGE_HEX_PATTERN.matcher(text).replaceAll("&$1");
        }

        return text;
    }

    /**
     * Processes MiniMessage format to legacy color codes.
     * This is a basic conversion that handles common tags.
     *
     * @param text the text to process
     * @return the text with MiniMessage converted to legacy codes
     * @throws IllegalArgumentException if text is null
     */
    public static String processMiniMessageToLegacy(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }

        // Handle hex colors first
        text = MINIMESSAGE_HEX_PATTERN.matcher(text).replaceAll("&$1");

        // Handle basic MiniMessage tags
        text = text.replaceAll("<black>", "&0")
                .replaceAll("<dark_blue>", "&1")
                .replaceAll("<dark_green>", "&2")
                .replaceAll("<dark_aqua>", "&3")
                .replaceAll("<dark_red>", "&4")
                .replaceAll("<dark_purple>", "&5")
                .replaceAll("<gold>", "&6")
                .replaceAll("<gray>", "&7")
                .replaceAll("<dark_gray>", "&8")
                .replaceAll("<blue>", "&9")
                .replaceAll("<green>", "&a")
                .replaceAll("<aqua>", "&b")
                .replaceAll("<red>", "&c")
                .replaceAll("<light_purple>", "&d")
                .replaceAll("<yellow>", "&e")
                .replaceAll("<white>", "&f")
                .replaceAll("<bold>", "&l")
                .replaceAll("<italic>", "&o")
                .replaceAll("<underlined>", "&n")
                .replaceAll("<strikethrough>", "&m")
                .replaceAll("<obfuscated>", "&k")
                .replaceAll("<reset>", "&r");

        // Remove closing tags
        text = text.replaceAll("</[^>]+>", "");

        return text;
    }

    /**
     * Processes legacy color codes to MiniMessage format.
     *
     * @param text the text to process
     * @return the text with legacy codes converted to MiniMessage
     * @throws IllegalArgumentException if text is null
     */
    public static String processLegacyToMiniMessage(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }

        // Handle hex colors first
        text = LEGACY_HEX_PATTERN.matcher(text).replaceAll("<$1>");

        // Handle basic legacy codes
        text = text.replaceAll("&0", "<black>")
                .replaceAll("&1", "<dark_blue>")
                .replaceAll("&2", "<dark_green>")
                .replaceAll("&3", "<dark_aqua>")
                .replaceAll("&4", "<dark_red>")
                .replaceAll("&5", "<dark_purple>")
                .replaceAll("&6", "<gold>")
                .replaceAll("&7", "<gray>")
                .replaceAll("&8", "<dark_gray>")
                .replaceAll("&9", "<blue>")
                .replaceAll("&a", "<green>")
                .replaceAll("&b", "<aqua>")
                .replaceAll("&c", "<red>")
                .replaceAll("&d", "<light_purple>")
                .replaceAll("&e", "<yellow>")
                .replaceAll("&f", "<white>")
                .replaceAll("&l", "<bold>")
                .replaceAll("&o", "<italic>")
                .replaceAll("&n", "<underlined>")
                .replaceAll("&m", "<strikethrough>")
                .replaceAll("&k", "<obfuscated>")
                .replaceAll("&r", "<reset>");

        return text;
    }

    /**
     * Strips all color codes from the text.
     *
     * @param text the text to strip
     * @return the text with all color codes removed
     * @throws IllegalArgumentException if text is null
     */
    public static String stripColors(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }

        // Strip all color code formats
        text = STRIP_LEGACY_PATTERN.matcher(text).replaceAll("");
        text = STRIP_SECTION_PATTERN.matcher(text).replaceAll("");
        text = STRIP_HEX_PATTERN.matcher(text).replaceAll("");
        text = STRIP_MINIMESSAGE_PATTERN.matcher(text).replaceAll("");

        return text;
    }

    /**
     * Gets the length of text without color codes.
     * This is useful for checking character limits.
     *
     * @param text the text to measure
     * @return the length without color codes
     * @throws IllegalArgumentException if text is null
     */
    public static int getStrippedLength(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }
        return stripColors(text).length();
    }

    /**
     * Checks if the text contains any color codes.
     *
     * @param text the text to check
     * @return true if the text contains color codes, false otherwise
     * @throws IllegalArgumentException if text is null
     */
    public static boolean hasColorCodes(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }

        return LEGACY_COLOR_PATTERN.matcher(text).find() ||
                SECTION_COLOR_PATTERN.matcher(text).find() ||
                LEGACY_HEX_PATTERN.matcher(text).find() ||
                MINIMESSAGE_PATTERN.matcher(text).find();
    }

    /**
     * Validates that a hex color code is properly formatted.
     *
     * @param hexCode the hex code to validate (e.g., "#FF0000")
     * @return true if the hex code is valid, false otherwise
     * @throws IllegalArgumentException if hexCode is null
     */
    public static boolean isValidHexColor(String hexCode) {
        if (hexCode == null) {
            throw new IllegalArgumentException("Hex code cannot be null");
        }
        return hexCode.matches("#[0-9a-fA-F]{6}");
    }

    /**
     * Truncates text to a specified length while preserving color codes.
     * The length is calculated based on the visible characters (excluding color codes).
     *
     * @param text      the text to truncate
     * @param maxLength the maximum visible length
     * @return the truncated text with color codes preserved
     * @throws IllegalArgumentException if text is null or maxLength is negative
     */
    public static String truncateWithColors(String text, int maxLength) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }
        if (maxLength < 0) {
            throw new IllegalArgumentException("Max length cannot be negative");
        }

        if (getStrippedLength(text) <= maxLength) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        int visibleLength = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // Check for color codes
            if (c == '&' || c == '§') {
                if (i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (isColorCode(next)) {
                        result.append(c).append(next);
                        i++; // Skip the next character
                        continue;
                    }
                }
            } else if (c == '<') {
                // Check for MiniMessage tags
                int closeIndex = text.indexOf('>', i);
                if (closeIndex != -1) {
                    String tag = text.substring(i, closeIndex + 1);
                    if (isMiniMessageTag(tag)) {
                        result.append(tag);
                        i = closeIndex;
                        continue;
                    }
                }
            }

            // Regular character
            if (visibleLength >= maxLength) {
                break;
            }

            result.append(c);
            visibleLength++;
        }

        return result.toString();
    }

    /**
     * Checks if a character is a valid color code character.
     *
     * @param c the character to check
     * @return true if the character is a valid color code, false otherwise
     */
    private static boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') ||
                (c >= 'a' && c <= 'f') ||
                (c >= 'A' && c <= 'F') ||
                (c >= 'k' && c <= 'o') ||
                (c >= 'K' && c <= 'O') ||
                c == 'r' || c == 'R';
    }

    /**
     * Checks if a string is a valid MiniMessage tag.
     *
     * @param tag the tag to check
     * @return true if the tag is valid, false otherwise
     */
    private static boolean isMiniMessageTag(String tag) {
        return MINIMESSAGE_PATTERN.matcher(tag).matches() ||
                MINIMESSAGE_HEX_PATTERN.matcher(tag).matches();
    }
}