package sh.harold.fulcrum.velocity.fundamentals.motd;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class CenteredTextFormatter {

    // Minecraft MOTD baseline font width per character (approximated)
    private static final int MAX_PIXELS = 154;

    String center(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String stripped = PlainTextComponentSerializer.plainText()
                .serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(input));
        int pixelWidth = stripped.length() * 4; // rough width estimation
        int paddingPixels = (MAX_PIXELS - pixelWidth) / 2;
        int spaces = Math.max(0, paddingPixels / 4);
        return " ".repeat(spaces) + input;
    }
}
