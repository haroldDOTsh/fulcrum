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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.world.poi.POIRegistry;
import sh.harold.fulcrum.fundamentals.world.model.LoadedWorld;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final PaperRuntime runtime;

    public WorldManager(Plugin plugin, WorldService worldService, POIRegistry poiRegistry, PaperRuntime runtime) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.poiRegistry = poiRegistry;
        this.logger = plugin.getLogger();
        this.runtime = runtime;
    }

    public CompletableFuture<WorldPasteResult> pasteWorld(UUID databaseRowId, World targetWorld, Location pasteLocation) {
        runtime.requirePrimary("create world paste request");
        if (targetWorld == null || pasteLocation == null) {
            return CompletableFuture.completedFuture(new WorldPasteResult(false, "Target world and paste location are required"));
        }
        return pasteWorld(databaseRowId,
            targetWorld.getName(),
            pasteLocation.getBlockX(),
            pasteLocation.getBlockY(),
            pasteLocation.getBlockZ());
    }

    public CompletableFuture<WorldPasteResult> pasteWorld(UUID databaseRowId, String targetWorldName, int x, int y, int z) {
        LoadedWorld loadedWorld = worldService.getWorldById(databaseRowId).orElse(null);
        if (loadedWorld == null) {
            return CompletableFuture.completedFuture(new WorldPasteResult(false, "World not found in database: " + databaseRowId));
        }

        return runtime.supplyAsync("load cached schematic " + loadedWorld.getWorldName(), () -> loadClipboard(loadedWorld))
            .thenCompose(clipboard -> runtime.callSync("paste cached schematic " + loadedWorld.getWorldName(), () -> {
                World targetWorld = Bukkit.getWorld(targetWorldName);
                if (targetWorld == null) {
                    return new WorldPasteResult(false, "Target world is not loaded: " + targetWorldName);
                }
                return pasteLoadedWorld(loadedWorld, clipboard, targetWorld, new Location(targetWorld, x, y, z));
            }))
            .exceptionally(throwable -> {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                    ? throwable.getCause()
                    : throwable;
                logger.log(Level.SEVERE, "Failed to paste world " + loadedWorld.getWorldName(), cause);
                return new WorldPasteResult(false, "Failed to paste world: " + cause.getMessage());
            });
    }

    private Clipboard loadClipboard(LoadedWorld world) {
        File schematicFile = world.getSchematicFile();
        if (schematicFile == null || !schematicFile.exists()) {
            throw new IllegalStateException("Cached schematic missing for world: " + world.getWorldName());
        }

        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            throw new IllegalStateException("Unknown schematic format: " + schematicFile.getName());
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(schematicFile))) {
            return reader.read();
        } catch (Exception e) {
            throw new CompletionException("Failed to read cached schematic " + schematicFile.getName(), e);
        }
    }

    private WorldPasteResult pasteLoadedWorld(LoadedWorld world, Clipboard clipboard, World targetWorld, Location pasteLocation) {
        runtime.requirePrimary("apply cached schematic");
        try {
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

