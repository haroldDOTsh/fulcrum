package sh.harold.fulcrum.velocity.fundamentals.motd;

import sh.harold.fulcrum.common.text.font.MinecraftFont;
import sh.harold.fulcrum.common.text.font.MinecraftFonts;

final class CenteredTextFormatter {

    private static final float LINE_WIDTH_PX = 235.0f;
    private static final MinecraftFont FONT = MinecraftFonts.defaultFont();

    String center(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        float width = FONT.measureLegacyText(input);
        if (width <= 0.0f) {
            return input;
        }

        float remainingPixels = LINE_WIDTH_PX - width;
        if (remainingPixels <= 0.0f) {
            return input;
        }

        int frontSpaces = calculateFrontPadding(remainingPixels);
        if (frontSpaces <= 0) {
            return input;
        }

        return " ".repeat(frontSpaces) + input;
    }

    private int calculateFrontPadding(float availablePixels) {
        float spaceWidth = FONT.spaceAdvance();
        if (spaceWidth <= 0.0f || availablePixels <= 0.0f) {
            return 0;
        }

        float halfPaddingPixels = availablePixels / 2.0f;
        float spaceUnits = halfPaddingPixels / spaceWidth;
        return Math.max(0, Math.round(spaceUnits));
    }
}
