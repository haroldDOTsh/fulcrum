package sh.harold.fulcrum.velocity.fundamentals.motd;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.common.text.font.MinecraftFont;
import sh.harold.fulcrum.common.text.font.MinecraftFonts;

import static org.assertj.core.api.Assertions.assertThat;

class CenteredTextFormatterTest {

    private static final float LINE_WIDTH_PX = 235.0f;

    private final CenteredTextFormatter formatter = new CenteredTextFormatter();
    private final MinecraftFont font = MinecraftFonts.defaultFont();

    @Test
    void centerAddsFrontPaddingWithinMotdWidth() {
        String text = "&aPlay Fulcrum";

        String centered = formatter.center(text);
        int leadingSpaces = countLeadingSpaces(centered);
        int trailingSpaces = countTrailingSpaces(centered);

        float width = font.measureLegacyText(text);
        float available = LINE_WIDTH_PX - width;
        float spaceWidth = font.spaceAdvance();
        int expectedSpaces = Math.round((available / 2.0f) / spaceWidth);

        assertThat(expectedSpaces)
                .as("precondition: expected front padding should be non-negative")
                .isGreaterThanOrEqualTo(0);
        assertThat(leadingSpaces).isEqualTo(expectedSpaces);
        assertThat(trailingSpaces).isZero();
        assertThat(centered.substring(leadingSpaces)).isEqualTo(text);
    }

    @Test
    void centerReturnsOriginalTextWhenLineAlreadyTooWide() {
        String text = buildStringWiderThanLine();
        assertThat(formatter.center(text)).isEqualTo(text);
    }

    @Test
    void centerConvertsBlankInputToEmptyString() {
        assertThat(formatter.center(null)).isEmpty();
        assertThat(formatter.center("   ")).isEmpty();
    }

    private String buildStringWiderThanLine() {
        StringBuilder builder = new StringBuilder("W");
        while (font.measureLegacyText(builder.toString()) <= LINE_WIDTH_PX) {
            builder.append('W');
        }
        return builder.toString();
    }

    private int countLeadingSpaces(String input) {
        int count = 0;
        while (count < input.length() && input.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private int countTrailingSpaces(String input) {
        int count = 0;
        for (int i = input.length() - 1; i >= 0 && input.charAt(i) == ' '; i--) {
            count++;
        }
        return count;
    }
}
