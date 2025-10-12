package sh.harold.fulcrum.api.world.paste;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * Represents a 3D region in the world.
 */
public class Region {

    private final World world;
    private final Vector min;
    private final Vector max;

    public Region(World world, Vector min, Vector max) {
        this.world = world;
        this.min = Vector.getMinimum(min, max);
        this.max = Vector.getMaximum(min, max);
    }

    public Region(Location corner1, Location corner2) {
        if (!corner1.getWorld().equals(corner2.getWorld())) {
            throw new IllegalArgumentException("Corners must be in the same world");
        }
        this.world = corner1.getWorld();
        this.min = Vector.getMinimum(corner1.toVector(), corner2.toVector());
        this.max = Vector.getMaximum(corner1.toVector(), corner2.toVector());
    }

    /**
     * Get the world this region is in.
     */
    public World getWorld() {
        return world;
    }

    /**
     * Get the minimum corner of the region.
     */
    public Vector getMin() {
        return min.clone();
    }

    /**
     * Get the maximum corner of the region.
     */
    public Vector getMax() {
        return max.clone();
    }

    /**
     * Get the minimum corner as a Location.
     */
    public Location getMinLocation() {
        return min.toLocation(world);
    }

    /**
     * Get the maximum corner as a Location.
     */
    public Location getMaxLocation() {
        return max.toLocation(world);
    }

    /**
     * Get the center of the region.
     */
    public Vector getCenter() {
        return min.clone().add(max).multiply(0.5);
    }

    /**
     * Get the center as a Location.
     */
    public Location getCenterLocation() {
        return getCenter().toLocation(world);
    }

    /**
     * Get the size of the region.
     */
    public Vector getSize() {
        return max.clone().subtract(min).add(new Vector(1, 1, 1));
    }

    /**
     * Get the volume of the region.
     */
    public int getVolume() {
        Vector size = getSize();
        return (int) (size.getX() * size.getY() * size.getZ());
    }

    /**
     * Check if a location is within this region.
     */
    public boolean contains(Location location) {
        if (!location.getWorld().equals(world)) {
            return false;
        }
        return contains(location.toVector());
    }

    /**
     * Check if a vector is within this region.
     */
    public boolean contains(Vector vector) {
        return vector.isInAABB(min, max);
    }

    /**
     * Expand the region by the given amount in all directions.
     */
    public Region expand(int amount) {
        Vector expansion = new Vector(amount, amount, amount);
        return new Region(world, min.clone().subtract(expansion), max.clone().add(expansion));
    }

    /**
     * Contract the region by the given amount in all directions.
     */
    public Region contract(int amount) {
        return expand(-amount);
    }

    @Override
    public String toString() {
        return String.format("Region[world=%s, min=%s, max=%s]",
                world.getName(), min, max);
    }
}