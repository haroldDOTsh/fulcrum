package sh.harold.fulcrum.common.text.font;

import java.util.Collections;
import java.util.Map;

/**
 * Represents a loaded Minecraft font atlas with glyph advances measured in client pixels.
 */
public final class MinecraftFont {

    private final Map<Integer, Float> advances;
    private final float spaceAdvance;
    private final float fallbackAdvance;

    MinecraftFont(Map<Integer, Float> advances) {
        this.advances = Collections.unmodifiableMap(advances);
        this.spaceAdvance = this.advances.getOrDefault((int) ' ', 4.0f);
        this.fallbackAdvance = this.advances.getOrDefault((int) '?', this.spaceAdvance);
    }

    /**
     * Returns the horizontal advance for the given code point, applying bold state if required.
     */
    public float advance(int codePoint, boolean bold) {
        float base = advances.getOrDefault(codePoint, fallbackAdvance);
        return bold ? base + 1.0f : base;
    }

    /**
     * Returns the configured advance for the space character, used when calculating padding.
     */
    public float spaceAdvance() {
        return spaceAdvance;
    }

    /**
     * Measures a legacy (§ / & colour code) formatted string and returns its rendered pixel width.
     */
    public float measureLegacyText(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }

        boolean expectingCode = false;
        boolean bold = false;
        float width = 0.0f;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            if (codePoint == '\u00a7' || codePoint == '&') {
                expectingCode = true;
                continue;
            }

            if (expectingCode) {
                expectingCode = false;
                char modifier = Character.toLowerCase((char) codePoint);
                if (modifier == 'l') {
                    bold = true;
                } else if (modifier == 'r' || isColorCode(modifier)) {
                    bold = false;
                }
                continue;
            }

            width += advance(codePoint, bold);
        }

        return width;
    }

    private boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    Map<Integer, Float> advances() {
        return advances;
    }
}
