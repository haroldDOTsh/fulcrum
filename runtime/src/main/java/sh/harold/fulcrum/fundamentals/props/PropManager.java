package sh.harold.fulcrum.fundamentals.props;

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
import sh.harold.fulcrum.fundamentals.props.model.PropDefinition;
import sh.harold.fulcrum.fundamentals.props.model.PropInstance;
import sh.harold.fulcrum.fundamentals.props.model.PropPlacementOptions;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles pasting cached props into live worlds and tracking their lifecycle.
 */
public class PropManager {
    private final Plugin plugin;
    private final PropService propService;
    private final Logger logger;

    public PropManager(Plugin plugin, PropService propService) {
        this.plugin = plugin;
        this.propService = propService;
        this.logger = plugin.getLogger();
    }

    public Optional<PropInstance> pasteProp(String propName,
                                            World world,
                                            Location baseOrigin,
                                            PropPlacementOptions options) {
        if (propName == null || propName.isBlank()) {
            return Optional.empty();
        }
        return propService.getPropByName(propName)
                .flatMap(definition -> pasteProp(definition, world, baseOrigin, options));
    }

    public Optional<PropInstance> pasteProp(PropDefinition prop,
                                            World world,
                                            Location baseOrigin,
                                            PropPlacementOptions options) {
        if (prop == null || world == null || baseOrigin == null) {
            return Optional.empty();
        }
        PropPlacementOptions safeOptions = options != null ? options : PropPlacementOptions.builder().build();
        File schematicFile = prop.getSchematicFile();
        if (schematicFile == null || !schematicFile.exists()) {
            logger.warning("Cached schematic missing for prop '" + prop.getName() + "'");
            return Optional.empty();
        }

        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            logger.warning("Unknown schematic format for prop '" + prop.getName() + "'");
            return Optional.empty();
        }

        BlockVector3 pasteBase = BlockVector3.at(
                baseOrigin.getBlockX(),
                baseOrigin.getBlockY() + safeOptions.verticalOffset(),
                baseOrigin.getBlockZ()
        );

        try (FileInputStream inputStream = new FileInputStream(schematicFile);
             ClipboardReader reader = format.getReader(inputStream)) {
            Clipboard clipboard = reader.read();
            BlockVector3 clipboardOrigin = clipboard.getOrigin() != null ? clipboard.getOrigin() : BlockVector3.ZERO;
            BlockVector3 pasteOffset = pasteBase.subtract(clipboardOrigin);

            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                ClipboardHolder holder = new ClipboardHolder(clipboard);
                Operation operation = holder.createPaste(editSession)
                        .to(pasteBase)
                        .ignoreAirBlocks(safeOptions.ignoreAirBlocks())
                        .build();
                Operations.complete(operation);
                editSession.flushQueue();
            }

            Location spawnLocation = resolveSpawnLocation(prop, pasteOffset, world, safeOptions);
            BlockVector3 regionMin = clipboard.getRegion().getMinimumPoint().add(pasteOffset);
            BlockVector3 regionMax = clipboard.getRegion().getMaximumPoint().add(pasteOffset);
            return Optional.of(new PropInstance(plugin, world, prop, regionMin, regionMax, spawnLocation));
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Failed to read prop schematic '" + prop.getName() + "'", exception);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to paste prop '" + prop.getName() + "'", exception);
        }
        return Optional.empty();
    }

    private Location resolveSpawnLocation(PropDefinition prop,
                                          BlockVector3 pasteOffset,
                                          World world,
                                          PropPlacementOptions options) {
        String preferred = options.spawnPoiKey();
        if (preferred == null && prop.getMetadata().has("spawnPoi")) {
            preferred = prop.getMetadata().get("spawnPoi").getAsString();
        }

        if (prop.getPois() != null && !prop.getPois().isEmpty()) {
            for (PoiDefinition poi : prop.getPois()) {
                if (isSpawnMatch(poi, preferred)) {
                    return toAbsolute(world, pasteOffset, poi);
                }
            }
        }

        return new Location(
                world,
                pasteOffset.x() + 0.5D,
                pasteOffset.y() + 1.0D,
                pasteOffset.z() + 0.5D
        );
    }

    private boolean isSpawnMatch(PoiDefinition poi, String preferredKey) {
        if (preferredKey == null || preferredKey.isBlank()) {
            return "spawn".equalsIgnoreCase(poi.type());
        }
        return preferredKey.equalsIgnoreCase(Optional.ofNullable(poi.identifier()).orElse(""))
                || preferredKey.equalsIgnoreCase(poi.type());
    }

    private Location toAbsolute(World world, BlockVector3 pasteOffset, PoiDefinition poi) {
        BlockVector3 relative = poi.position();
        double x = pasteOffset.x() + relative.x() + 0.5D;
        double y = pasteOffset.y() + relative.y();
        double z = pasteOffset.z() + relative.z() + 0.5D;
        float yaw = poi.metadata().has("yaw") ? poi.metadata().get("yaw").getAsFloat() : 0.0F;
        float pitch = poi.metadata().has("pitch") ? poi.metadata().get("pitch").getAsFloat() : 0.0F;
        return new Location(world, x, y, z, yaw, pitch);
    }
}
