package sh.harold.fulcrum.api.world.impl;

import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.world.WorldManager;
import sh.harold.fulcrum.api.world.generator.VoidChunkGenerator;
import sh.harold.fulcrum.api.world.paste.*;
import sh.harold.fulcrum.api.world.paste.impl.FAWEWorldPaster;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;

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
    private final PaperRuntime runtime;
    private final Map<String, File> templates = new ConcurrentHashMap<>();
    private final File schematicsDirectory;
    
    @Deprecated
    public DefaultWorldManager(Plugin plugin) {
        this(plugin, null);
    }

    public DefaultWorldManager(Plugin plugin, PaperRuntime runtime) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.runtime = runtime;
        this.worldPaster = new FAWEWorldPaster(plugin, runtime);
        
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
        return callSync("reset world " + world.getName(), () -> {
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
        
        String worldName = world.getName();
        return callSync("prepare world reset from template " + worldName, () -> {
            try {
                // Clear entities first
                world.getEntities().stream()
                        .filter(entity -> !(entity instanceof org.bukkit.entity.Player))
                        .forEach(org.bukkit.entity.Entity::remove);
                return new Location(world, 0, 64, 0);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to prepare world reset: " + worldName, e);
                return null;
            }
        }).thenCompose(spawnLocation -> {
            if (spawnLocation == null) {
                return CompletableFuture.completedFuture(false);
            }

            PasteOptions options = PasteOptions.builder()
                    .ignoreAirBlocks(false)
                    .copyEntities(true)
                    .copyBiomes(true)
                    .fastMode(true)
                    .build();

            return worldPaster.pasteSchematic(templateSchematic, spawnLocation, options)
                    .thenCompose(result -> callSync("finish world reset from template " + worldName, () -> {
                        if (result.isSuccess()) {
                            world.setSpawnLocation(spawnLocation);
                            logger.info("Reset world " + worldName + " from template: " + templateSchematic.getName());
                            return true;
                        }
                        logger.warning("Failed to reset world from template: " + result.getErrorMessage().orElse("Unknown error"));
                        return false;
                    }));
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
        
        return callSync("create world shell " + worldName, () -> {
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

                return world;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to create world from template: " + worldName, e);
                return null;
            }
        }).thenCompose(world -> {
            if (world == null) {
                return CompletableFuture.completedFuture(null);
            }

            Location origin = new Location(world, 0, 64, 0);
            return worldPaster.pasteSchematic(templateSchematic, origin, options)
                    .thenCompose(result -> callSync("finish world template paste " + worldName, () -> {
                        if (result.isSuccess()) {
                            world.setSpawnLocation(origin);
                            logger.info("Created world " + worldName + " from template: " + templateSchematic.getName());
                            return world;
                        }

                        logger.warning("Failed to paste template into new world: " +
                                result.getErrorMessage().orElse("Unknown error"));
                        Bukkit.unloadWorld(world, false);
                        return null;
                    }));
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
        logger.warning("Saving regions as schematics is not yet implemented");
        return CompletableFuture.completedFuture(false);
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
        
        // Paste the minigame template
        PasteOptions options = PasteOptions.builder()
                .ignoreAirBlocks(false)
                .copyEntities(true)
                .copyBiomes(true)
                .fastMode(true)
                .trackProgress(true)
                .build();
        
        return callSync("prepare minigame world " + world.getName(), () -> {
            world.getEntities().stream()
                    .filter(entity -> !(entity instanceof org.bukkit.entity.Player))
                    .forEach(org.bukkit.entity.Entity::remove);
            return world.getSpawnLocation();
        }).thenCompose(origin -> worldPaster.pasteSchematic(template, origin, options));
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

    private <T> CompletableFuture<T> callSync(String operation, java.util.function.Supplier<T> supplier) {
        if (runtime != null) {
            return runtime.callSync(operation, supplier);
        }
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(new IllegalStateException(operation + " requires the Paper primary thread"));
        }
        try {
            return CompletableFuture.completedFuture(supplier.get());
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }
}


