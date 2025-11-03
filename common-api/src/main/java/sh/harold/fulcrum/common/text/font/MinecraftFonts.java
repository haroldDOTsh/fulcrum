package sh.harold.fulcrum.common.text.font;

/**
 * Convenience entry points for accessing the packaged Minecraft font metrics.
 */
public final class MinecraftFonts {

    private static final MinecraftFont DEFAULT_FONT = new MinecraftFontLoader(MinecraftFonts.class.getClassLoader()).loadDefault();

    private MinecraftFonts() {
    }

    public static MinecraftFont defaultFont() {
        return DEFAULT_FONT;
    }
}
