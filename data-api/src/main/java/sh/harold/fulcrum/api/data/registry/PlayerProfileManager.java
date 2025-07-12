package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class PlayerProfileManager {
    private static final Logger LOGGER = Logger.getLogger(PlayerProfileManager.class.getName());
    private static final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    private PlayerProfileManager() {
    }

    public static PlayerProfile load(UUID playerId) {
        if (isLoaded(playerId)) {
            return get(playerId);
        }

        PlayerProfile profile = new PlayerProfile(playerId);
        profile.loadAll();

        if (profile.isNew()) {
            LOGGER.info("New player profile detected for " + playerId + ". Saving initial data.");
            profile.saveAll();
        }

        profiles.put(playerId, profile);
        return profile;
    }

    public static PlayerProfile get(UUID playerId) {
        return profiles.get(playerId);
    }

    public static boolean isLoaded(UUID playerId) {
        return profiles.containsKey(playerId);
    }

    public static void unload(UUID playerId) {
        var profile = profiles.remove(playerId);
        if (profile != null) {
            LOGGER.info("Unloading and saving player profile for " + playerId + ".");
            profile.saveAll();
        }
    }
    
    // ========================
    // Query Builder Support
    // ========================
    
    /**
     * Finds all players matching a query across schemas.
     *
     * @param query The query to execute
     * @return A CompletableFuture containing matching player profiles
     * @since 2.0
     */
    public static CompletableFuture<List<PlayerProfile>> findPlayers(CrossSchemaQueryBuilder query) {
        return query.executeAsync()
            .thenCompose(results -> {
                Set<UUID> playerIds = results.stream()
                    .map(CrossSchemaResult::getPlayerUuid)
                    .collect(Collectors.toSet());
                return loadProfiles(playerIds);
            });
    }
    
    /**
     * Loads multiple player profiles.
     *
     * @param playerIds The UUIDs of players to load
     * @return A CompletableFuture containing the loaded profiles
     * @since 2.0
     */
    public static CompletableFuture<List<PlayerProfile>> loadProfiles(Collection<UUID> playerIds) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerProfile> loadedProfiles = new ArrayList<>();
            for (UUID playerId : playerIds) {
                PlayerProfile profile = load(playerId);
                loadedProfiles.add(profile);
            }
            return loadedProfiles;
        });
    }
    
    /**
     * Gets all currently loaded profiles.
     *
     * @return Collection of loaded player profiles
     * @since 2.0
     */
    public static Collection<PlayerProfile> getLoadedProfiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }
    
    /**
     * Gets all currently loaded player UUIDs.
     *
     * @return Set of loaded player UUIDs
     * @since 2.0
     */
    public static Set<UUID> getLoadedPlayerIds() {
        return Collections.unmodifiableSet(profiles.keySet());
    }
    
    /**
     * Unloads all player profiles, saving them first.
     *
     * @since 2.0
     */
    public static void unloadAll() {
        LOGGER.info("Unloading all " + profiles.size() + " player profiles...");
        
        // Save all profiles
        for (PlayerProfile profile : profiles.values()) {
            profile.saveAll();
        }
        
        // Clear the cache
        profiles.clear();
        
        LOGGER.info("All player profiles unloaded successfully.");
    }
    
}
