package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
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
        LOGGER.info("[DIAGNOSTIC] PlayerProfileManager.unload() called for player: " + playerId);
        
        var profile = profiles.remove(playerId);
        if (profile != null) {
            LOGGER.info("[DIAGNOSTIC] Profile found for player: " + playerId);
            
            // Check if profile has dirty data before saving
            int dirtyCount = profile.getDirtyDataCount();
            LOGGER.info("[DIAGNOSTIC] Player " + playerId + " has " + dirtyCount + " dirty data entries before save");
            
            LOGGER.info("Unloading and saving player profile for " + playerId + ".");
            LOGGER.info("[DIAGNOSTIC] Calling profile.saveAll()...");
            
            try {
                profile.saveAll();
                LOGGER.info("[DIAGNOSTIC] profile.saveAll() completed successfully for player: " + playerId);
                
                // Check dirty data count after save
                int dirtyCountAfter = profile.getDirtyDataCount();
                LOGGER.info("[DIAGNOSTIC] Player " + playerId + " has " + dirtyCountAfter + " dirty data entries after save");
                
                // Verify save was successful
                if (dirtyCountAfter > 0) {
                    LOGGER.warning("[DIAGNOSTIC] WARNING: Player " + playerId + " still has " + dirtyCountAfter + " dirty data entries after save - this may indicate a persistence issue");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DIAGNOSTIC] CRITICAL: Failed to save profile during unload for player " + playerId, e);
                // Re-add profile to prevent data loss
                profiles.put(playerId, profile);
                throw new RuntimeException("Failed to save profile during unload for player " + playerId, e);
            }
        } else {
            LOGGER.info("[DIAGNOSTIC] No profile found for player: " + playerId + " (already unloaded or never loaded)");
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
     * @since 1.0.0
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
     * @since 1.0.0
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
     * @since 1.0.0
     */
    public static Collection<PlayerProfile> getLoadedProfiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }
    
    /**
     * Gets all currently loaded player UUIDs.
     *
     * @return Set of loaded player UUIDs
     * @since 1.0.0
     */
    public static Set<UUID> getLoadedPlayerIds() {
        return Collections.unmodifiableSet(profiles.keySet());
    }
    
    /**
     * Unloads all player profiles, saving them first.
     *
     * @since 1.0.0
     */
    public static void unloadAll() {
        LOGGER.info("Unloading all " + profiles.size() + " player profiles...");
        
        int successCount = 0;
        int failureCount = 0;
        List<UUID> failedProfiles = new ArrayList<>();
        
        // Save all profiles
        for (PlayerProfile profile : profiles.values()) {
            try {
                profile.saveAll();
                successCount++;
            } catch (Exception e) {
                failureCount++;
                failedProfiles.add(profile.getPlayerId());
                LOGGER.log(Level.SEVERE, "Failed to save profile during unloadAll for player " + profile.getPlayerId(), e);
            }
        }
        
        // Clear the cache (even for failed saves to prevent memory leaks)
        profiles.clear();
        
        if (failureCount > 0) {
            LOGGER.log(Level.WARNING, "Unloaded all profiles with {0} successes and {1} failures. Failed profiles: {2}",
                    new Object[]{successCount, failureCount, failedProfiles});
        } else {
            LOGGER.info("All " + successCount + " player profiles unloaded successfully.");
        }
    }
    
}
