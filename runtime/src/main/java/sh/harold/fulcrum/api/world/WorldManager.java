package sh.harold.fulcrum.api.world;

import org.bukkit.World;
import sh.harold.fulcrum.api.world.paste.PasteOptions;
import sh.harold.fulcrum.api.world.paste.PasteResult;
import sh.harold.fulcrum.api.world.paste.WorldPaster;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Manager for world operations including pasting, resetting, and template management.
 */
public interface WorldManager {

    /**
     * Get the world paster for schematic operations.
     *
     * @return The world paster instance
     */
    WorldPaster getWorldPaster();

    /**
     * Reset a world to its original state or from a template.
     *
     * @param world The world to reset
     * @return A future that completes when the reset is done
     */
    CompletableFuture<Boolean> resetWorld(World world);

    /**
     * Reset a world using a specific schematic template.
     *
     * @param world             The world to reset
     * @param templateSchematic The template schematic file
     * @return A future that completes when the reset is done
     */
    CompletableFuture<Boolean> resetWorld(World world, File templateSchematic);

    /**
     * Create a world from a schematic template.
     *
     * @param worldName         The name for the new world
     * @param templateSchematic The template schematic file
     * @return A future containing the created world, or null if failed
     */
    CompletableFuture<World> createWorldFromTemplate(String worldName, File templateSchematic);

    /**
     * Create a world from a schematic template with options.
     *
     * @param worldName         The name for the new world
     * @param templateSchematic The template schematic file
     * @param options           Paste options for the template
     * @return A future containing the created world, or null if failed
     */
    CompletableFuture<World> createWorldFromTemplate(String worldName, File templateSchematic, PasteOptions options);

    /**
     * Save a world or region as a schematic.
     *
     * @param world      The world to save
     * @param outputFile The output schematic file
     * @return A future that completes when the save is done
     */
    CompletableFuture<Boolean> saveWorldAsSchematic(World world, File outputFile);

    /**
     * Save a specific region as a schematic.
     *
     * @param region     The region to save
     * @param outputFile The output schematic file
     * @return A future that completes when the save is done
     */
    CompletableFuture<Boolean> saveRegionAsSchematic(sh.harold.fulcrum.api.world.paste.Region region, File outputFile);

    /**
     * Register a template schematic for quick access.
     *
     * @param name          The template name
     * @param schematicFile The schematic file
     * @return true if registered successfully
     */
    boolean registerTemplate(String name, File schematicFile);

    /**
     * Get a registered template by name.
     *
     * @param name The template name
     * @return The template file, or null if not found
     */
    File getTemplate(String name);

    /**
     * Check if a template is registered.
     *
     * @param name The template name
     * @return true if the template exists
     */
    boolean hasTemplate(String name);

    /**
     * Unregister a template.
     *
     * @param name The template name
     * @return true if unregistered successfully
     */
    boolean unregisterTemplate(String name);

    /**
     * Get the directory where schematics are stored.
     *
     * @return The schematics directory
     */
    File getSchematicsDirectory();

    /**
     * Set up a world for minigame use with a template.
     *
     * @param world        The world to set up
     * @param templateName The template to use
     * @return A future containing the paste result
     */
    CompletableFuture<PasteResult> setupMinigameWorld(World world, String templateName);
}