package sh.harold.fulcrum.fundamentals.props.model;

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
    private final AtomicBoolean removed = new AtomicBoolean(false);

    public PropInstance(Plugin plugin,
                        World world,
                        PropDefinition definition,
                        BlockVector3 regionMin,
                        BlockVector3 regionMax,
                        Location spawnLocation) {
        this.plugin = plugin;
        this.world = world;
        this.definition = definition;
        this.regionMin = regionMin;
        this.regionMax = regionMax;
        this.spawnLocation = spawnLocation;
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
        }
    }
}
