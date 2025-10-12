package sh.harold.fulcrum.minigame.environment;

import org.bukkit.*;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.world.generator.VoidChunkGenerator;
import sh.harold.fulcrum.fundamentals.world.WorldManager;
import sh.harold.fulcrum.fundamentals.world.WorldManager.WorldPasteResult;
import sh.harold.fulcrum.fundamentals.world.WorldService;
import sh.harold.fulcrum.fundamentals.world.model.LoadedWorld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Shared helper that prepares per-slot match worlds by pasting cached schematics.
 * Modules may request environments via the service locator.
 */
public class MinigameEnvironmentService {
    private static final String DEFAULT_MAP_ID = "test";

    private final Logger logger;
    private final WorldService worldService;
    private final WorldManager worldManager;
    private final ConcurrentMap<String, MatchEnvironment> environments = new ConcurrentHashMap<>();

    public MinigameEnvironmentService(Logger logger,
                                      WorldService worldService,
                                      WorldManager worldManager) {
        this.logger = logger;
        this.worldService = worldService;
        this.worldManager = worldManager;
    }

    public MatchEnvironment prepareEnvironment(String slotId, Map<String, String> metadata) {
        if (worldService == null || worldManager == null) {
            logger.warning("World services unavailable; cannot prepare environment for slot " + slotId);
            return null;
        }

        MatchEnvironment existing = environments.get(slotId);
        if (existing != null) {
            return existing;
        }

        String mapId = metadata != null && metadata.containsKey("mapId")
                ? metadata.get("mapId") : DEFAULT_MAP_ID;

        Optional<LoadedWorld> mapOptional = resolveMap(mapId);
        if (mapOptional.isEmpty()) {
            logger.warning("No cached world definition found for mapId '" + mapId + "'");
            return null;
        }

        LoadedWorld map = mapOptional.get();
        String worldName = generateWorldName(slotId, mapId);
        unloadIfPresent(worldName);

        World world = createVoidWorld(worldName);
        if (world == null) {
            return null;
        }

        configureRules(world);

        Location pasteLocation = new Location(world, 0, 64, 0);
        WorldPasteResult pasteResult;
        try {
            pasteResult = worldManager.pasteWorld(map.getId(), world, pasteLocation).join();
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to paste map '" + mapId + "' for slot " + slotId, exception);
            cleanupWorld(worldName);
            return null;
        }

        if (pasteResult == null || !pasteResult.isSuccess()) {
            logger.warning("Unable to prepare map '" + mapId + "' for slot " + slotId + ": "
                    + (pasteResult != null ? pasteResult.getMessage() : "unknown"));
            cleanupWorld(worldName);
            return null;
        }

        Location matchSpawn = resolveMatchSpawn(map, pasteLocation);
        Location lobbySpawn = resolvePreLobbySpawn(map, pasteLocation, matchSpawn);
        world.setSpawnLocation(lobbySpawn);

        MatchEnvironment environment = new MatchEnvironment(slotId, worldName, mapId,
                lobbySpawn, matchSpawn);
        environments.put(slotId, environment);
        logger.info(() -> "Prepared match environment '" + worldName + "' for slot " + slotId
                + " (mapId=" + mapId + ")");
        return environment;
    }

    public Optional<MatchEnvironment> getEnvironment(String slotId) {
        return Optional.ofNullable(environments.get(slotId));
    }

    public void cleanup(String slotId) {
        MatchEnvironment environment = environments.remove(slotId);
        if (environment != null) {
            cleanupWorld(environment.worldName());
        }
    }

    private Optional<LoadedWorld> resolveMap(String mapId) {
        Optional<LoadedWorld> byId = worldService.getWorldByMapId(mapId);
        if (byId.isPresent()) {
            return byId;
        }
        return worldService.getWorldByName(mapId);
    }

    private String generateWorldName(String slotId, String mapId) {
        String slotSuffix = slotId.replaceAll("[^A-Za-z0-9]", "");
        String mapSuffix = mapId.replaceAll("[^A-Za-z0-9]", "");
        return ("match_" + slotSuffix + "_" + mapSuffix).toLowerCase(Locale.ROOT);
    }

    private void unloadIfPresent(String worldName) {
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            Bukkit.unloadWorld(existing, false);
        }
        cleanupWorld(worldName);
    }

    private World createVoidWorld(String worldName) {
        WorldCreator creator = new WorldCreator(worldName);
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.FLAT);
        creator.generator(new VoidChunkGenerator());
        creator.generateStructures(false);
        return creator.createWorld();
    }

    private void configureRules(World world) {
        world.setAutoSave(false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setTime(6000L);
    }

    private Location resolveMatchSpawn(LoadedWorld map, Location pasteLocation) {
        return map.getPois().stream()
                .filter(poi -> "origin".equalsIgnoreCase(poi.type()))
                .findFirst()
                .map(poi -> pasteLocation.clone().add(
                        poi.position().x() + 0.5D,
                        poi.position().y(),
                        poi.position().z() + 0.5D))
                .orElseGet(() -> pasteLocation.clone().add(0.5D, 1.0D, 0.5D));
    }

    private Location resolvePreLobbySpawn(LoadedWorld map, Location pasteLocation, Location matchSpawn) {
        return map.getPois().stream()
                .filter(poi -> "preorigin".equalsIgnoreCase(poi.type()))
                .findFirst()
                .map(poi -> pasteLocation.clone().add(
                        poi.position().x() + 0.5D,
                        poi.position().y(),
                        poi.position().z() + 0.5D))
                .orElse(matchSpawn.clone());
    }

    private void cleanupWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            Location fallback = !Bukkit.getWorlds().isEmpty()
                    ? Bukkit.getWorlds().get(0).getSpawnLocation() : null;
            for (Player player : List.copyOf(world.getPlayers())) {
                if (fallback != null) {
                    player.teleport(fallback);
                }
            }
            Bukkit.unloadWorld(world, false);
        }

        Path worldPath = Bukkit.getServer().getWorldContainer().toPath().resolve(worldName);
        if (!Files.exists(worldPath)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(worldPath)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    logger.log(Level.WARNING, "Failed to remove world file " + path, exception);
                }
            });
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Failed to delete world directory " + worldPath, exception);
        }
    }

    public record MatchEnvironment(String slotId,
                                   String worldName,
                                   String mapId,
                                   Location lobbySpawn,
                                   Location matchSpawn) {
    }
}



