package sh.harold.fulcrum.message.util;

import java.util.regex.Pattern;

/**
 * Utility class for handling color code conversions and message formatting.
 * Converts legacy color codes to Adventure Component format.
 */
public class ColorUtil {
    
    // Pattern to match legacy color codes (&a, &1, etc.)
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("&([0-9a-fk-or])");
    
    // Pattern to match hex color codes (&#RRGGBB)
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    
    /**
     * Converts legacy color codes and hex codes to Adventure Component format.
     * 
     * @param text The text with legacy color codes
     * @return The text converted to Adventure format
     */
    public static String convertToAdventure(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // Convert hex colors first (&#RRGGBB -> <color:#RRGGBB>)
        text = HEX_COLOR_PATTERN.matcher(text).replaceAll("<color:#$1>");
        
        // Convert legacy colors (&a -> <green>, &1 -> <dark_blue>, etc.)
        text = LEGACY_COLOR_PATTERN.matcher(text).replaceAll(match -> {
            char code = match.group(1).charAt(0);
            return switch (code) {
                case '0' -> "<black>";
                case '1' -> "<dark_blue>";
                case '2' -> "<dark_green>";
                case '3' -> "<dark_aqua>";
                case '4' -> "<dark_red>";
                case '5' -> "<dark_purple>";
                case '6' -> "<gold>";
                case '7' -> "<gray>";
                case '8' -> "<dark_gray>";
                case '9' -> "<blue>";
                case 'a' -> "<green>";
                case 'b' -> "<aqua>";
                case 'c' -> "<red>";
                case 'd' -> "<light_purple>";
                case 'e' -> "<yellow>";
                case 'f' -> "<white>";
                case 'k' -> "<obfuscated>";
                case 'l' -> "<bold>";
                case 'm' -> "<strikethrough>";
                case 'n' -> "<underlined>";
                case 'o' -> "<italic>";
                case 'r' -> "<reset>";
                default -> "&" + code; // Fallback for unrecognized codes
            };
        });
        
        return text;
    }
    
    /**
     * Strips all color codes from text.
     * 
     * @param text The text to strip colors from
     * @return The text without color codes
     */
    public static String stripColors(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // Remove hex colors
        text = HEX_COLOR_PATTERN.matcher(text).replaceAll("");
        
        // Remove legacy colors
        text = LEGACY_COLOR_PATTERN.matcher(text).replaceAll("");
        
        return text;
    }
    
    /**
     * Applies a color code to text if it doesn't already have colors.
     * 
     * @param text The text to color
     * @param colorCode The color code to apply (e.g., "&a")
     * @return The colored text
     */
    public static String applyColorIfNeeded(String text, String colorCode) {
        if (text == null || colorCode == null) {
            return text;
        }
        
        // Check if text already has color codes
        if (LEGACY_COLOR_PATTERN.matcher(text).find() || HEX_COLOR_PATTERN.matcher(text).find()) {
            return text;
        }
        
        return colorCode + text;
    }
    
    /**
     * Formats arguments with the specified color.
     * 
     * @param text The text containing {0}, {1}, etc. placeholders
     * @param argumentColor The color to apply to arguments
     * @param args The arguments to insert
     * @return The formatted text with colored arguments
     */
    public static String formatWithColoredArgs(String text, String argumentColor, Object... args) {
        if (args == null || args.length == 0) {
            return text;
        }
        
        String result = text;
        for (int i = 0; i < args.length; i++) {
            String placeholder = "{" + i + "}";
            if (result.contains(placeholder)) {
                String argValue = args[i] != null ? args[i].toString() : "";
                String coloredArg = argumentColor != null ? argumentColor + argValue : argValue;
                result = result.replace(placeholder, coloredArg);
            }
        }
        
        return result;
    }
}
