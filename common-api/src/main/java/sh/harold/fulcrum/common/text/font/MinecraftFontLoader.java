package sh.harold.fulcrum.common.text.font;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.List;

/**
 * Loads Minecraft font definitions (bitmaps, spaces, TTF) into {@link MinecraftFont} instances.
 */
public final class MinecraftFontLoader {

    private static final String DEFAULT_FONT_RESOURCE = "minecraft:font/default.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClassLoader classLoader;

    public MinecraftFontLoader() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public MinecraftFontLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public MinecraftFont loadDefault() {
        Map<Integer, Float> advances = new HashMap<>();
        Set<String> visited = new HashSet<>();
        loadDefinition(DEFAULT_FONT_RESOURCE, advances, visited);
        return new MinecraftFont(advances);
    }

    private void loadDefinition(String resource, Map<Integer, Float> advances, Set<String> visited) {
        if (!visited.add(resource)) {
            return;
        }

        JsonNode root = readJson(resource);
        JsonNode providers = root.path("providers");
        if (!providers.isArray()) {
            return;
        }

        for (JsonNode provider : providers) {
            String type = provider.path("type").asText();
            switch (type) {
                case "reference" -> loadReference(provider, advances, visited);
                case "space" -> loadSpace(provider, advances);
                case "bitmap" -> loadBitmap(provider, advances);
                case "ttf" -> loadTrueType(provider, advances);
                case "legacy_unicode" -> loadLegacyUnicode(provider, advances);
                default -> {
                    // Ignore unknown providers for now.
                }
            }
        }
    }

    private void loadReference(JsonNode node, Map<Integer, Float> advances, Set<String> visited) {
        String id = node.path("id").asText();
        if (id.isEmpty()) {
            return;
        }

        String namespaced = normalizeNamespace(id);
        String target = namespaced.substring(0, namespaced.indexOf(':')) + ":font/" + namespaced.substring(namespaced.indexOf(':') + 1) + ".json";
        loadDefinition(target, advances, visited);
    }

    private void loadSpace(JsonNode node, Map<Integer, Float> advances) {
        JsonNode advancesNode = node.path("advances");
        if (!advancesNode.isObject()) {
            return;
        }
        advancesNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode valueNode = entry.getValue();
            if (key == null || key.isEmpty()) {
                return;
            }
            float value = valueNode.isNumber() ? valueNode.floatValue() : 0.0f;
            key.codePoints().forEach(codePoint -> advances.put(codePoint, value));
        });
    }

    private void loadBitmap(JsonNode node, Map<Integer, Float> advances) {
        String file = node.path("file").asText();
        JsonNode charsNode = node.path("chars");
        int height = node.path("height").asInt();
        if (file.isEmpty() || !charsNode.isArray() || height <= 0) {
            return;
        }

        BufferedImage image = readImage(resolveTexture(normalizeNamespace(file)));
        if (image == null) {
            return;
        }

        List<int[]> rows = new ArrayList<>();
        int columns = 0;
        for (JsonNode rowNode : charsNode) {
            String row = rowNode.asText();
            int[] codePoints = row.codePoints().toArray();
            rows.add(codePoints);
            if (codePoints.length > columns) {
                columns = codePoints.length;
            }
        }

        if (columns == 0) {
            return;
        }

        int cellWidth = image.getWidth() / columns;

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            int[] codePoints = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < codePoints.length; columnIndex++) {
                int codePoint = codePoints[columnIndex];
                float advance = measureBitmapGlyph(image, rowIndex, columnIndex, cellWidth, height);
                advances.put(codePoint, advance);
            }
        }
    }

    private float measureBitmapGlyph(BufferedImage image, int rowIndex, int columnIndex, int cellWidth, int cellHeight) {
        int startX = columnIndex * cellWidth;
        int startY = rowIndex * cellHeight;
        int endX = Math.min(startX + cellWidth, image.getWidth()) - 1;
        int endY = Math.min(startY + cellHeight, image.getHeight()) - 1;

        int left = cellWidth;
        int right = -1;

        for (int y = startY; y <= endY; y++) {
            for (int x = startX; x <= endX; x++) {
                int alpha = (image.getRGB(x, y) >> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int relative = x - startX;
                if (relative < left) {
                    left = relative;
                }
                if (relative > right) {
                    right = relative;
                }
            }
        }

        if (right < 0) {
            return 0.0f;
        }

        int glyphWidth = right - left + 1;
        return (glyphWidth / 2.0f) + 1.0f;
    }

    private void loadTrueType(JsonNode node, Map<Integer, Float> advances) {
        String file = node.path("file").asText();
        if (file.isEmpty()) {
            return;
        }

        float size = node.path("size").floatValue();
        if (size <= 0.0f) {
            size = 11.0f;
        }
        float oversample = node.path("oversample").floatValue();
        if (oversample <= 0.0f) {
            oversample = 1.0f;
        }

        Set<Integer> skipped = new HashSet<>();
        JsonNode skipNode = node.path("skip");
        if (skipNode.isArray()) {
            for (JsonNode entry : skipNode) {
                entry.asText().codePoints().forEach(skipped::add);
            }
        }

        List<int[]> rows = new ArrayList<>();
        JsonNode charsNode = node.path("chars");
        if (charsNode.isArray()) {
            for (JsonNode rowNode : charsNode) {
                rows.add(rowNode.asText().codePoints().toArray());
            }
        }

        Font font = loadFont(normalizeNamespace(file), size);
        if (font == null) {
            return;
        }

        FontRenderContext context = new FontRenderContext(new AffineTransform(), true, true);
        for (int[] row : rows) {
            for (int codePoint : row) {
                if (skipped.contains(codePoint)) {
                    continue;
                }
                char[] chars = Character.toChars(codePoint);
                GlyphVector vector = font.createGlyphVector(context, new String(chars));
                GlyphMetrics metrics = vector.getGlyphMetrics(0);
                float advance = metrics.getAdvanceX() / oversample;
                advances.put(codePoint, advance);
            }
        }
    }

    private void loadLegacyUnicode(JsonNode node, Map<Integer, Float> advances) {
        String base = node.path("file").asText("textures/font/unicode_page_{:02x}.png");
        String normalized = normalizeNamespace(base);
        String namespace = normalized.substring(0, normalized.indexOf(':'));
        String pathTemplate = normalized.substring(normalized.indexOf(':') + 1);

        for (int page = 0; page < 256; page++) {
            String formatted = formatLegacyPath(pathTemplate, page);
            BufferedImage image = readImage(resolveTexture(namespace + ":" + formatted));
            if (image == null) {
                continue;
            }
            int cellWidth = image.getWidth() / 16;
            int cellHeight = image.getHeight() / 16;
            for (int row = 0; row < 16; row++) {
                for (int column = 0; column < 16; column++) {
                    int codePoint = (page << 8) | (row << 4) | column;
                    float advance = measureBitmapGlyph(image, row, column, cellWidth, cellHeight);
                    if (advance > 0.0f) {
                        advances.put(codePoint, advance);
                    }
                }
            }
        }
    }

    private String formatLegacyPath(String template, int page) {
        String hex = String.format("%02x", page);
        return template.replace("{:02x}", hex).replace("%02x", hex);
    }

    private JsonNode readJson(String resource) {
        String path = toResourcePath(resource);
        try (InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing font resource: " + path);
            }
            return objectMapper.readTree(in);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read font resource: " + path, ex);
        }
    }

    private BufferedImage readImage(String resource) {
        String path = toResourcePath(resource);
        try (InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }

    private Font loadFont(String resource, float size) {
        String path = toResourcePath(resource);
        try (InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            Font base = Font.createFont(Font.TRUETYPE_FONT, in);
            return base.deriveFont(size);
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveTexture(String resource) {
        String namespaced = normalizeNamespace(resource);
        String namespace = namespaced.substring(0, namespaced.indexOf(':'));
        String path = namespaced.substring(namespaced.indexOf(':') + 1);
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        return namespace + ":" + path;
    }

    private String normalizeNamespace(String resource) {
        if (resource.contains(":")) {
            return resource;
        }
        return "minecraft:" + resource;
    }

    private String toResourcePath(String resource) {
        int colon = resource.indexOf(':');
        String namespace = colon >= 0 ? resource.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? resource.substring(colon + 1) : resource;
        return "assets/" + namespace + "/" + path;
    }
}
