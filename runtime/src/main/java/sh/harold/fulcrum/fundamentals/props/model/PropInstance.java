package sh.harold.fulcrum.fundamentals.props.model;

import com.google.gson.JsonObject;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.world.poi.POIRegistry;
import sh.harold.fulcrum.npc.poi.PoiActivationBus;
import sh.harold.fulcrum.npc.poi.PoiDeactivatedEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Tracks a pasted prop so it can be referenced and cleaned up.
 */
public final class PropInstance {
    private final Plugin plugin;
    private final World world;
    private final PropDefinition definition;
    private final BlockVector3 regionMin;
    private final BlockVector3 regionMax;
    private final Location spawnLocation;
    private final POIRegistry poiRegistry;
    private final PoiActivationBus activationBus;
    private final List<PoiRegistration> registeredPois;
    private final AtomicBoolean removed = new AtomicBoolean(false);

    public PropInstance(Plugin plugin,
                        World world,
                        PropDefinition definition,
                        BlockVector3 regionMin,
                        BlockVector3 regionMax,
                        Location spawnLocation,
                        POIRegistry poiRegistry,
                        PoiActivationBus activationBus,
                        List<PoiRegistration> registeredPois) {
        this.plugin = plugin;
        this.world = world;
        this.definition = definition;
        this.regionMin = regionMin;
        this.regionMax = regionMax;
        this.spawnLocation = spawnLocation;
        this.poiRegistry = poiRegistry;
        this.activationBus = activationBus;
        this.registeredPois = registeredPois != null ? List.copyOf(registeredPois) : List.of();
    }

    public PropDefinition definition() {
        return definition;
    }

    public Location spawnLocation() {
        return spawnLocation.clone();
    }

    public void remove() {
        if (!removed.compareAndSet(false, true)) {
            return;
        }
        try {
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                CuboidRegion region = new CuboidRegion(weWorld, regionMin, regionMax);
                editSession.setBlocks((Region) region, BlockTypes.AIR.getDefaultState());
                editSession.flushQueue();
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to remove prop " + definition.getName(), exception);
        } finally {
            clearRegisteredPois();
        }
    }

    private void clearRegisteredPois() {
        if (registeredPois.isEmpty() || poiRegistry == null) {
            return;
        }
        for (PoiRegistration registration : registeredPois) {
            Location location = registration.location();
            poiRegistry.removePOI(world, location);
            if (activationBus != null) {
                activationBus.publishDeactivated(
                        new PoiDeactivatedEvent(world.getName(), location, registration.configuration()));
            }
        }
    }

    public record PoiRegistration(Location location, JsonObject configuration) {
        public PoiRegistration {
            location = location.clone();
            configuration = configuration.deepCopy();
        }

        @Override
        public Location location() {
            return location.clone();
        }

        @Override
        public JsonObject configuration() {
            return configuration.deepCopy();
        }
    }
}
