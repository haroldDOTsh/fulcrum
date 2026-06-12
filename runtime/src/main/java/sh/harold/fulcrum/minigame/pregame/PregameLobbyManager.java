package sh.harold.fulcrum.minigame.pregame;

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
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.world.WorldService;
import sh.harold.fulcrum.fundamentals.world.model.LoadedWorld;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

/**
 * Utilities for spawning and cleaning up the floating pre-game lobby structure.
 */
public final class PregameLobbyManager {
    private static final String PREORIGIN_TYPE = "preorigin";

    private PregameLobbyManager() {
    }

    public static Optional<PregameLobbyInstance> spawn(JavaPlugin plugin,
                                                       World world,
                                                       Location baseOrigin,
                                                       String schematicId,
                                                       int heightOffset) {
        if (plugin == null || world == null || baseOrigin == null || schematicId == null) {
            return Optional.empty();
        }

        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            plugin.getLogger().warning("Pregame lobby spawn requested but service locator is unavailable.");
            return Optional.empty();
        }

        WorldService worldService = locator.findService(WorldService.class).orElse(null);
        if (worldService == null) {
            plugin.getLogger().warning("Pregame lobby spawn requested but WorldService is not registered.");
            return Optional.empty();
        }

        Optional<LoadedWorld> maybeLobby = worldService.getWorldByName(schematicId);
        if (maybeLobby.isEmpty()) {
            plugin.getLogger().warning("Pre-game lobby schematic '" + schematicId + "' not found in world cache.");
            return Optional.empty();
        }

        LoadedWorld lobbyDefinition = maybeLobby.get();
        File schematicFile = lobbyDefinition.getSchematicFile();
        if (schematicFile == null || !schematicFile.exists()) {
            plugin.getLogger().warning("Cached schematic missing for pre-game lobby '" + schematicId + "'.");
            return Optional.empty();
        }

        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            plugin.getLogger().warning("Unknown schematic format for pre-game lobby '" + schematicId + "'.");
            return Optional.empty();
        }

        BlockVector3 pasteBase = BlockVector3.at(baseOrigin.getBlockX(), baseOrigin.getBlockY() + heightOffset, baseOrigin.getBlockZ());
        try (FileInputStream inputStream = new FileInputStream(schematicFile);
             ClipboardReader reader = format.getReader(inputStream)) {
            Clipboard clipboard = reader.read();
            List<PoiDefinition> pois = lobbyDefinition.getPois();

            BlockVector3 clipboardOrigin = clipboard.getOrigin();
            BlockVector3 pasteOffset = pasteBase.subtract(clipboardOrigin);

            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operation operation = new com.sk89q.worldedit.session.ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(pasteBase)
                    .ignoreAirBlocks(false)
                    .build();
                Operations.complete(operation);
                editSession.flushQueue();
            }

            Location spawnLocation = resolveSpawn(world, pasteOffset, pois);
            BlockVector3 regionMin = clipboard.getRegion().getMinimumPoint().add(pasteOffset);
            BlockVector3 regionMax = clipboard.getRegion().getMaximumPoint().add(pasteOffset);
            return Optional.of(new PregameLobbyInstance(plugin, world, regionMin, regionMax, spawnLocation));
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to read pre-game lobby schematic '" + schematicId + "': " + exception.getMessage());
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to paste pre-game lobby schematic '" + schematicId + "': " + exception.getMessage());
        }
        return Optional.empty();
    }

    private static Location resolveSpawn(World world, BlockVector3 pasteOffset, List<PoiDefinition> pois) {
        if (pois != null) {
            for (PoiDefinition poi : pois) {
                if (PREORIGIN_TYPE.equalsIgnoreCase(poi.type())) {
                    BlockVector3 relative = poi.position();
                    double x = pasteOffset.x() + relative.x() + 0.5D;
                    double y = pasteOffset.y() + relative.y();
                    double z = pasteOffset.z() + relative.z() + 0.5D;
                    return new Location(world, x, y, z);
                }
            }
        }
        return new Location(world, pasteOffset.x() + 0.5D, pasteOffset.y() + 1.0D, pasteOffset.z() + 0.5D);
    }

    public static final class PregameLobbyInstance {
        private final JavaPlugin plugin;
        private final World world;
        private final BlockVector3 worldMin;
        private final BlockVector3 worldMax;
        private final Location spawnLocation;
        private final AtomicBoolean removed = new AtomicBoolean(false);

        private PregameLobbyInstance(JavaPlugin plugin,
                                     World world,
                                     BlockVector3 worldMin,
                                     BlockVector3 worldMax,
                                     Location spawnLocation) {
            this.plugin = plugin;
            this.world = world;
            this.worldMin = worldMin;
            this.worldMax = worldMax;
            this.spawnLocation = spawnLocation;
        }

        public Location getSpawnLocation() {
            return spawnLocation.clone();
        }

        public void remove() {
            if (!removed.compareAndSet(false, true)) {
                return;
            }
            try {
                com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
                try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                    CuboidRegion region = new CuboidRegion(weWorld, worldMin, worldMax);
                    editSession.setBlocks((com.sk89q.worldedit.regions.Region) region, BlockTypes.AIR.getDefaultState());
                    editSession.flushQueue();
                }
            } catch (Exception exception) {
                plugin.getLogger().warning("Failed to remove pre-game lobby: " + exception.getMessage());
            }
        }
    }
}












