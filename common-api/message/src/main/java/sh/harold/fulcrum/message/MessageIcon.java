package sh.harold.fulcrum.message;

/**
 * Central registry of glyph shortcuts so callers can reuse icons without copy/paste.
 */
public enum MessageIcon {
    ROUND_BOLD_ARROW("➜");

    private final String glyph;

    MessageIcon(String glyph) {
        this.glyph = glyph;
    }

    public String glyph() {
        return glyph;
    }

    @Override
    public String toString() {
        return glyph;
    }
}
