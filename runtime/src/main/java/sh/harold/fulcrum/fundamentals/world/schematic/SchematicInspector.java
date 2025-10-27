package sh.harold.fulcrum.fundamentals.world.schematic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.jnbt.*;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.enginehub.linbus.tree.LinCompoundTag;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads schematics, extracts POIs/origin markers, and returns a cleaned clipboard ready for caching.
 */
public class SchematicInspector {

    private static final ClipboardFormat DEFAULT_FORMAT = resolveDefaultFormat();
    private final Logger logger;

    public SchematicInspector(Logger logger) {
        this.logger = logger;
    }

    public static ClipboardFormat defaultFormat() {
        if (DEFAULT_FORMAT == null) {
            throw new IllegalStateException("Unable to resolve .schem clipboard format");
        }
        return DEFAULT_FORMAT;
    }

    private static ClipboardFormat resolveDefaultFormat() {
        ClipboardFormat detected = detectDefaultFormat();
        return detected != null ? detected : BuiltInClipboardFormat.SPONGE_SCHEMATIC;
    }

    private static ClipboardFormat detectDefaultFormat() {
        // Prefer FAWE's fast reader; fall back through other common aliases before we settle on Sponge V2.
        ClipboardFormat format = tryFindFormat(() -> ClipboardFormats.findByAlias("fast"));
        if (format != null) {
            return format;
        }
        format = tryFindFormat(() -> ClipboardFormats.findByExtension("schem"));
        if (format != null) {
            return format;
        }
        format = tryFindFormat(() -> ClipboardFormats.findByAlias("schem"));
        if (format != null) {
            return format;
        }
        format = tryFindFormat(() -> ClipboardFormats.findByAlias("sponge"));
        if (format != null) {
            return format;
        }
        return tryFindFormat(() -> ClipboardFormats.findByAlias("sponge_v3"));
    }

    private static ClipboardFormat tryFindFormat(FormatSupplier supplier) {
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public InspectionResult inspect(byte[] schematicBytes, String debugName) throws IOException {
        if (DEFAULT_FORMAT == null) {
            throw new IllegalStateException("Unable to resolve .schem clipboard format");
        }
        Clipboard clipboard = readClipboard(schematicBytes, debugName);
        BlockArrayClipboard blockArrayClipboard = toBlockArrayClipboard(clipboard, debugName);
        return analyseClipboard(blockArrayClipboard, debugName);
    }

    private BlockArrayClipboard toBlockArrayClipboard(Clipboard clipboard, String debugName) {

        if (clipboard instanceof BlockArrayClipboard blockArrayClipboard) {

            return blockArrayClipboard;

        }


        Region region = clipboard.getRegion();

        BlockVector3 minimum = region.getMinimumPoint();

        BlockVector3 maximum = region.getMaximumPoint();


        BlockArrayClipboard copy = new BlockArrayClipboard(

                new com.sk89q.worldedit.regions.CuboidRegion(

                        BlockVector3.ZERO,

                        maximum.subtract(minimum)

                )

        );


        BlockVector3 origin = clipboard.getOrigin();

        copy.setOrigin(origin != null ? origin.subtract(minimum) : BlockVector3.ZERO);


        for (BlockVector3 absolute : region) {

            BaseBlock block = clipboard.getFullBlock(absolute);

            BlockVector3 relative = absolute.subtract(minimum);

            BaseBlock value = block != null ? block : BlockTypes.AIR.getDefaultState().toBaseBlock();

            try {

                copy.setBlock(relative, value);

            } catch (com.sk89q.worldedit.WorldEditException exception) {

                throw new IllegalStateException("Failed to materialize clipboard for inspection", exception);

            }

        }


        return copy;

    }

    private Clipboard readClipboard(byte[] schematicBytes, String debugName) throws IOException {
        List<Throwable> errors = new ArrayList<>();
        for (ClipboardFormat format : candidateFormats()) {
            if (format == null) {
                continue;
            }
            try (ByteArrayInputStream input = new ByteArrayInputStream(schematicBytes);
                 ClipboardReader reader = format.getReader(input)) {
                Clipboard clipboard = reader.read();
                if (format != DEFAULT_FORMAT && logger != null && logger.isLoggable(Level.FINE)) {
                    logger.fine("Loaded schematic " + debugName + " using fallback format " + format.getName());
                }
                return clipboard;
            } catch (IOException | RuntimeException exception) {
                if (logger != null && logger.isLoggable(Level.FINE)) {
                    logger.fine("Failed to load schematic " + debugName + " with format "
                            + format.getName() + ": " + exception.getMessage());
                }
                errors.add(exception);
            }
        }

        IOException failure = new IOException("Unable to read schematic " + debugName + " using supported formats");
        for (Throwable error : errors) {
            failure.addSuppressed(error);
        }
        throw failure;
    }

    private List<ClipboardFormat> candidateFormats() {
        Set<ClipboardFormat> formats = new LinkedHashSet<>();
        formats.add(DEFAULT_FORMAT);
        ClipboardFormat fastAlias = tryFindFormat(() -> ClipboardFormats.findByAlias("fast"));
        if (fastAlias != null) {
            formats.add(fastAlias);
        }
        ClipboardFormat schemExt = tryFindFormat(() -> ClipboardFormats.findByExtension("schem"));
        if (schemExt != null) {
            formats.add(schemExt);
        }
        ClipboardFormat schemAlias = tryFindFormat(() -> ClipboardFormats.findByAlias("schem"));
        if (schemAlias != null) {
            formats.add(schemAlias);
        }
        formats.add(BuiltInClipboardFormat.FAST_V3);
        formats.add(BuiltInClipboardFormat.FAST_V2);
        formats.add(BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC);
        formats.add(BuiltInClipboardFormat.SPONGE_V2_SCHEMATIC);
        formats.add(BuiltInClipboardFormat.SPONGE_V1_SCHEMATIC);
        formats.add(BuiltInClipboardFormat.MCEDIT_SCHEMATIC);
        formats.add(BuiltInClipboardFormat.BROKENENTITY);
        return List.copyOf(formats);
    }

    private InspectionResult analyseClipboard(BlockArrayClipboard clipboard, String debugName) {

        Region region = clipboard.getRegion();

        BlockVector3 detectedOrigin = null;

        List<RawPoi> rawPois = new ArrayList<>();


        for (BlockVector3 pos : region) {

            BlockVector3 current = pos.toImmutable();

            BlockStateHolder<?> block = clipboard.getFullBlock(current);

            if (!isSign(block)) {

                continue;

            }


            CompoundTag blockEntity = extractBlockEntity(block);

            List<String> lines = extractSignLines(blockEntity);

            if (lines.isEmpty()) {

                continue;

            }


            String header = normalise(lines.get(0));

            if ("[ORIGIN]".equalsIgnoreCase(header)) {

                detectedOrigin = current;
                JsonObject originMetadata = new JsonObject();
                rawPois.add(new RawPoi("origin", "origin", current, originMetadata));

                removeBlock(clipboard, current);

                continue;

            }


            if ("[POI]".equalsIgnoreCase(header)) {

                Map<String, String> attributes = parseAttributes(lines.subList(1, lines.size()));

                String type = attributes.remove("type");

                if (type == null || type.isBlank()) {

                    logger.warning(() -> "Skipping POI without type in schematic " + debugName + " at " + current);

                    removeBlock(clipboard, current);

                    continue;

                }

                String id = attributes.remove("id");

                JsonObject metadata = new JsonObject();

                attributes.forEach(metadata::addProperty);

                rawPois.add(new RawPoi(id, type, current, metadata));

                removeBlock(clipboard, current);

            } else {

                removeBlock(clipboard, current);

            }

        }


        BlockVector3 origin = detectedOrigin != null ? detectedOrigin : clipboard.getOrigin();

        if (origin == null) {

            origin = region.getMinimumPoint();

        }

        clipboard.setOrigin(origin);


        List<PoiDefinition> pois = new ArrayList<>();

        for (RawPoi poi : rawPois) {

            BlockVector3 relative = poi.position.subtract(origin);

            pois.add(new PoiDefinition(poi.identifier, poi.type, relative, poi.metadata));

        }


        return new InspectionResult(clipboard, pois, detectedOrigin != null);

    }

    private CompoundTag extractBlockEntity(BlockStateHolder<?> block) {
        if (block == null) {
            return null;
        }
        BaseBlock baseBlock = block.toBaseBlock();
        LazyReference<LinCompoundTag> reference = baseBlock.getNbtReference();
        if (reference == null) {
            return null;
        }
        LinCompoundTag linCompound = reference.getValue();
        if (linCompound == null) {
            return null;
        }
        // TODO: replace LinBusConverter bridge before upgrading past FAWE/WE 2.11.x
        Tag tag = LinBusConverter.fromLinBus(linCompound);
        if (tag instanceof CompoundTag compoundTag) {
            return compoundTag;
        }
        return null;
    }

    private boolean isSign(BlockStateHolder<?> block) {
        if (block == null) {
            return false;
        }
        String id = block.getBlockType().id().toLowerCase(Locale.ROOT);
        return id.contains("sign");
    }

    private List<String> extractSignLines(CompoundTag tag) {
        if (tag == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        readSignSide(extractCompound(tag, "front_text"), lines);
        readSignSide(extractCompound(tag, "back_text"), lines);
        return lines.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @SuppressWarnings("rawtypes")
    private CompoundTag extractCompound(CompoundTag parent, String key) {
        if (parent == null) {
            return null;
        }
        Map value = parent.getValue();
        Object child = value.get(key);
        return child instanceof CompoundTag compoundTag ? compoundTag : null;
    }

    @SuppressWarnings("rawtypes")
    private void readSignSide(CompoundTag side, List<String> lines) {
        if (side == null) {
            return;
        }
        Map value = side.getValue();
        Object messagesTag = value.get("messages");
        if (!(messagesTag instanceof ListTag listTag)) {
            return;
        }
        List rawList = listTag.getValue();
        for (Object element : rawList) {
            if (element instanceof StringTag stringTag) {
                String raw = stringTag.getValue();
                String parsed = parseTextComponent(raw);
                if (parsed != null) {
                    lines.add(parsed);
                }
            }
        }
    }

    private String parseTextComponent(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "\"\"".equals(trimmed)) {
            return null;
        }
        try {
            if (trimmed.startsWith("{")) {
                JsonElement element = JsonParser.parseString(trimmed);
                if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("text")) {
                        return object.get("text").getAsString();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to raw handling
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private Map<String, String> parseAttributes(List<String> lines) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    JsonObject object = JsonParser.parseString(trimmed).getAsJsonObject();
                    object.entrySet().forEach(entry -> attrs.put(entry.getKey(), entry.getValue().getAsString()));
                    continue;
                } catch (Exception ignored) {
                    // fallback to key=value parsing below
                }
            }
            String[] segments = trimmed.split(",");
            for (String segment : segments) {
                String[] keyValue = segment.split("=", 2);
                if (keyValue.length == 2) {
                    attrs.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        return attrs;
    }

    private void removeBlock(BlockArrayClipboard clipboard, BlockVector3 pos) {
        clipboard.setBlock(pos, BlockTypes.AIR.getDefaultState());
    }

    private String normalise(String value) {
        return value == null ? "" : value.trim();
    }

    @FunctionalInterface
    private interface FormatSupplier {
        ClipboardFormat get();
    }

    private record RawPoi(String identifier, String type, BlockVector3 position, JsonObject metadata) {
    }

    public record InspectionResult(BlockArrayClipboard clipboard, List<PoiDefinition> pois, boolean originDetected) {
    }
}







