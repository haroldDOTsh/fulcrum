package sh.harold.fulcrum.velocity.fundamentals.motd;

import sh.harold.fulcrum.common.text.font.MinecraftFont;
import sh.harold.fulcrum.common.text.font.MinecraftFonts;

final class CenteredTextFormatter {

    private static final float CENTER_PX = 154.0f;
    private static final MinecraftFont FONT = MinecraftFonts.defaultFont();

    String center(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        float width = FONT.measureLegacyText(input);
        if (width <= 0.0f) {
            return input;
        }

        float paddingPixels = CENTER_PX - (width / 2.0f);
        if (paddingPixels <= 0.0f) {
            return input;
        }

        float spaceWidth = FONT.spaceAdvance();
        if (spaceWidth <= 0.0f) {
            return input;
        }

        int spaces = Math.max(0, Math.round(paddingPixels / spaceWidth));
        if (spaces == 0) {
            return input;
        }

        return " ".repeat(spaces) + input;
    }
}
