package sh.harold.fulcrum.api.world.impl;

import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.world.WorldManager;
import sh.harold.fulcrum.api.world.generator.VoidChunkGenerator;
import sh.harold.fulcrum.api.world.paste.PasteOptions;
import sh.harold.fulcrum.api.world.paste.PasteResult;
import sh.harold.fulcrum.api.world.paste.Region;
import sh.harold.fulcrum.api.world.paste.WorldPaster;
import sh.harold.fulcrum.api.world.paste.impl.FAWEWorldPaster;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of WorldManager using FAWE.
 */
public class DefaultWorldManager implements WorldManager {

    private final Plugin plugin;
    private final Logger logger;
    private final WorldPaster worldPaster;
    private final Map<String, File> templates = new ConcurrentHashMap<>();
    private final File schematicsDirectory;

    public DefaultWorldManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.worldPaster = new FAWEWorldPaster(plugin);

        // Create schematics directory
        this.schematicsDirectory = new File(plugin.getDataFolder(), "schematics");
        if (!schematicsDirectory.exists()) {
            schematicsDirectory.mkdirs();
        }

        // Load existing templates
        loadTemplates();
    }

    @Override
    public WorldPaster getWorldPaster() {
        return worldPaster;
    }

    @Override
    public CompletableFuture<Boolean> resetWorld(World world) {
        // Check if there's a default template for this world
        File template = getTemplate(world.getName() + "_template");
        if (template != null) {
            return resetWorld(world, template);
        }

        // Otherwise, just clear the world
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Clear entities (except players)
                world.getEntities().stream()
                        .filter(entity -> !(entity instanceof org.bukkit.entity.Player))
                        .forEach(org.bukkit.entity.Entity::remove);

                // Reset spawn location
                world.setSpawnLocation(0, world.getHighestBlockYAt(0, 0), 0);

                logger.info("Reset world: " + world.getName());
                return true;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to reset world: " + world.getName(), e);
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> resetWorld(World world, File templateSchematic) {
        if (!templateSchematic.exists()) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Clear entities first
                world.getEntities().stream()
                        .filter(entity -> !(entity instanceof org.bukkit.entity.Player))
                        .forEach(org.bukkit.entity.Entity::remove);

                // Determine spawn location
                Location spawnLocation = new Location(world, 0, 64, 0);

                // Paste the template
                PasteOptions options = PasteOptions.builder()
                        .ignoreAirBlocks(false)
                        .copyEntities(true)
                        .copyBiomes(true)
                        .fastMode(true)
                        .build();

                PasteResult result = worldPaster.pasteSchematic(templateSchematic, spawnLocation, options)
                        .join();

                if (result.isSuccess()) {
                    // Update spawn location if needed
                    world.setSpawnLocation(spawnLocation);
                    logger.info("Reset world " + world.getName() + " from template: " + templateSchematic.getName());
                    return true;
                } else {
                    logger.warning("Failed to reset world from template: " + result.getErrorMessage().orElse("Unknown error"));
                    return false;
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to reset world: " + world.getName(), e);
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<World> createWorldFromTemplate(String worldName, File templateSchematic) {
        return createWorldFromTemplate(worldName, templateSchematic, PasteOptions.defaults());
    }

    @Override
    public CompletableFuture<World> createWorldFromTemplate(String worldName, File templateSchematic,
                                                            PasteOptions options) {
        if (!templateSchematic.exists()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create a new world
                WorldCreator creator = new WorldCreator(worldName);
                creator.environment(World.Environment.NORMAL);
                creator.type(WorldType.FLAT);
                creator.generator(new VoidChunkGenerator());
                creator.generateStructures(false);
                World world = Bukkit.createWorld(creator);
                if (world == null) {
                    logger.severe("Failed to create world: " + worldName);
                    return null;
                }

                // Clear the flat world
                world.getEntities().stream()
                        .filter(entity -> !(entity instanceof org.bukkit.entity.Player))
                        .forEach(org.bukkit.entity.Entity::remove);

                // Paste the template
                Location origin = new Location(world, 0, 64, 0);
                PasteResult result = worldPaster.pasteSchematic(templateSchematic, origin, options).join();

                if (result.isSuccess()) {
                    world.setSpawnLocation(origin);
                    logger.info("Created world " + worldName + " from template: " + templateSchematic.getName());
                    return world;
                } else {
                    logger.warning("Failed to paste template into new world: " +
                            result.getErrorMessage().orElse("Unknown error"));
                    // Unload the failed world
                    Bukkit.unloadWorld(world, false);
                    return null;
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to create world from template: " + worldName, e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> saveWorldAsSchematic(World world, File outputFile) {
        // Calculate world bounds (this is a simplified approach)
        int radius = 256; // Default radius to save
        Location center = world.getSpawnLocation();

        Region region = new Region(
                center.clone().add(-radius, -64, -radius),
                center.clone().add(radius, 320, radius)
        );

        return saveRegionAsSchematic(region, outputFile);
    }

    @Override
    public CompletableFuture<Boolean> saveRegionAsSchematic(Region region, File outputFile) {
        // This would require additional FAWE API for saving
        // For now, return a not-implemented response
        return CompletableFuture.supplyAsync(() -> {
            logger.warning("Saving regions as schematics is not yet implemented");
            return false;
        });
    }

    @Override
    public boolean registerTemplate(String name, File schematicFile) {
        if (!schematicFile.exists()) {
            logger.warning("Cannot register template - file does not exist: " + schematicFile.getPath());
            return false;
        }

        templates.put(name.toLowerCase(), schematicFile);
        logger.info("Registered template: " + name);
        return true;
    }

    @Override
    public File getTemplate(String name) {
        return templates.get(name.toLowerCase());
    }

    @Override
    public boolean hasTemplate(String name) {
        return templates.containsKey(name.toLowerCase());
    }

    @Override
    public boolean unregisterTemplate(String name) {
        File removed = templates.remove(name.toLowerCase());
        if (removed != null) {
            logger.info("Unregistered template: " + name);
            return true;
        }
        return false;
    }

    @Override
    public File getSchematicsDirectory() {
        return schematicsDirectory;
    }

    @Override
    public CompletableFuture<PasteResult> setupMinigameWorld(World world, String templateName) {
        File template = getTemplate(templateName);
        if (template == null) {
            String operationId = java.util.UUID.randomUUID().toString();
            return CompletableFuture.completedFuture(
                    PasteResult.failure(operationId, "Template not found: " + templateName, java.time.Instant.now())
            );
        }

        // Clear the world first
        world.getEntities().stream()
                .filter(entity -> !(entity instanceof org.bukkit.entity.Player))
                .forEach(org.bukkit.entity.Entity::remove);

        // Paste the minigame template
        PasteOptions options = PasteOptions.builder()
                .ignoreAirBlocks(false)
                .copyEntities(true)
                .copyBiomes(true)
                .fastMode(true)
                .trackProgress(true)
                .build();

        Location origin = world.getSpawnLocation();
        return worldPaster.pasteSchematic(template, origin, options);
    }

    private void loadTemplates() {
        // Load templates from the schematics directory
        File[] schematicFiles = schematicsDirectory.listFiles((dir, name) ->
                name.endsWith(".schem") || name.endsWith(".schematic"));

        if (schematicFiles != null) {
            for (File schematic : schematicFiles) {
                String templateName = schematic.getName()
                        .replace(".schem", "")
                        .replace(".schematic", "");
                registerTemplate(templateName, schematic);
            }
        }
    }
}


