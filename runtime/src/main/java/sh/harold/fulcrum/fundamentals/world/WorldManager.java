package sh.harold.fulcrum.fundamentals.world;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.world.poi.POIRegistry;
import sh.harold.fulcrum.fundamentals.world.model.LoadedWorld;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages pasting cached schematics into live Bukkit worlds and applying POIs.
 */
public class WorldManager {
    private final Plugin plugin;
    private final WorldService worldService;
    private final POIRegistry poiRegistry;
    private final Logger logger;

    public WorldManager(Plugin plugin, WorldService worldService, POIRegistry poiRegistry) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.poiRegistry = poiRegistry;
        this.logger = plugin.getLogger();
    }

    public CompletableFuture<WorldPasteResult> pasteWorld(UUID databaseRowId, World targetWorld, Location pasteLocation) {
        return CompletableFuture.supplyAsync(() ->
                worldService.getWorldById(databaseRowId)
                        .map(world -> pasteLoadedWorld(world, targetWorld, pasteLocation))
                        .orElseGet(() -> new WorldPasteResult(false, "World not found in database: " + databaseRowId))
        );
    }

    private WorldPasteResult pasteLoadedWorld(LoadedWorld world, World targetWorld, Location pasteLocation) {
        File schematicFile = world.getSchematicFile();
        if (schematicFile == null || !schematicFile.exists()) {
            return new WorldPasteResult(false, "Cached schematic missing for world: " + world.getWorldName());
        }

        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            return new WorldPasteResult(false, "Unknown schematic format: " + schematicFile.getName());
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(schematicFile))) {
            Clipboard clipboard = reader.read();
            com.sk89q.worldedit.world.World adaptedWorld = BukkitAdapter.adapt(targetWorld);
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(adaptedWorld)) {
                BlockVector3 pastePos = BlockVector3.at(
                        pasteLocation.getBlockX(),
                        pasteLocation.getBlockY(),
                        pasteLocation.getBlockZ()
                );
                if (clipboard.getOrigin() == null) {
                    clipboard.setOrigin(BlockVector3.ZERO);
                }

                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(pastePos)
                        .ignoreAirBlocks(false)
                        .build();

                Operations.complete(operation);
                applyPOIs(world, targetWorld, pasteLocation);
                logger.info("Pasted world " + world.getWorldName() + " (" + world.getMapId() + ") at " + pasteLocation);
                return new WorldPasteResult(true, "World pasted successfully", world);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to paste world " + world.getWorldName(), e);
            return new WorldPasteResult(false, "Failed to paste world: " + e.getMessage());
        }
    }

    private void applyPOIs(LoadedWorld world, World targetWorld, Location baseLocation) {
        poiRegistry.clearWorldPOIs(targetWorld.getName());
        List<PoiDefinition> pois = world.getPois();
        for (PoiDefinition poi : pois) {
            Location absolute = baseLocation.clone().add(
                    poi.position().x(),
                    poi.position().y(),
                    poi.position().z()
            );
            poiRegistry.registerPOI(targetWorld, absolute, poi.toConfigJson());
            logger.fine(() -> String.format(Locale.ROOT,
                    "Registered POI %s[%s] at %s", poi.identifier(), poi.type(), absolute));
        }
    }

    public static class WorldPasteResult {
        private final boolean success;
        private final String message;
        private final LoadedWorld world;

        public WorldPasteResult(boolean success, String message) {
            this(success, message, null);
        }

        public WorldPasteResult(boolean success, String message, LoadedWorld world) {
            this.success = success;
            this.message = message;
            this.world = world;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public LoadedWorld getWorld() {
            return world;
        }
    }
}

