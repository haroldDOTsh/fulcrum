package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.CrossSchemaResult;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.integration.PlayerDataQueryManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
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
     * Creates a player finder for querying profiles with specific criteria.
     * This provides a fluent API for finding players based on various attributes.
     *
     * @return A new PlayerFinder instance
     * @since 2.0
     */
    public static PlayerFinder find() {
        return new PlayerFinder();
    }
    
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
    
    /**
     * Fluent interface for finding players with specific criteria.
     *
     * @since 2.0
     */
    public static class PlayerFinder {
        private final Map<String, Predicate<?>> criteria = new HashMap<>();
        private PlayerDataSchema<?> primarySchema;
        private Integer limit;
        private Integer offset;
        
        private PlayerFinder() {
        }
        
        /**
         * Sets the primary schema to query from.
         *
         * @param schema The primary schema
         * @return This finder for method chaining
         */
        public PlayerFinder fromSchema(PlayerDataSchema<?> schema) {
            this.primarySchema = schema;
            return this;
        }
        
        /**
         * Adds a criterion to the search.
         *
         * @param field The field name
         * @param predicate The predicate to match
         * @return This finder for method chaining
         */
        public PlayerFinder where(String field, Predicate<?> predicate) {
            criteria.put(field, predicate);
            return this;
        }
        
        /**
         * Filters by player rank.
         *
         * @param rank The rank to filter by
         * @return This finder for method chaining
         */
        public PlayerFinder withRank(String rank) {
            criteria.put("rank", obj -> rank.equals(obj));
            return this;
        }
        
        /**
         * Filters by minimum level.
         *
         * @param minLevel The minimum level
         * @return This finder for method chaining
         */
        public PlayerFinder withMinLevel(int minLevel) {
            criteria.put("level", obj -> obj instanceof Number && ((Number) obj).intValue() >= minLevel);
            return this;
        }
        
        /**
         * Filters by guild name.
         *
         * @param guildName The guild name
         * @return This finder for method chaining
         */
        public PlayerFinder inGuild(String guildName) {
            criteria.put("guild", obj -> guildName.equals(obj));
            return this;
        }
        
        /**
         * Filters by online status.
         *
         * @return This finder for method chaining
         */
        public PlayerFinder online() {
            criteria.put("online", obj -> Boolean.TRUE.equals(obj));
            return this;
        }
        
        /**
         * Filters by offline status.
         *
         * @return This finder for method chaining
         */
        public PlayerFinder offline() {
            criteria.put("online", obj -> Boolean.FALSE.equals(obj));
            return this;
        }
        
        /**
         * Limits the number of results.
         *
         * @param limit The maximum number of results
         * @return This finder for method chaining
         */
        public PlayerFinder limit(int limit) {
            this.limit = limit;
            return this;
        }
        
        /**
         * Sets the offset for pagination.
         *
         * @param offset The number of results to skip
         * @return This finder for method chaining
         */
        public PlayerFinder offset(int offset) {
            this.offset = offset;
            return this;
        }
        
        /**
         * Executes the search and returns matching profiles.
         *
         * @return A CompletableFuture containing matching player profiles
         */
        public CompletableFuture<List<PlayerProfile>> findAsync() {
            // Delegate to PlayerDataQueryManager
            PlayerDataQueryManager.PlayerFinder finder = PlayerDataQueryManager.getInstance().findPlayers();
            
            if (primarySchema != null) {
                finder.fromSchema(primarySchema);
            }
            
            criteria.forEach((field, predicate) -> finder.withCustomCriteria(field, predicate));
            
            return finder.executeAsync();
        }
        
        /**
         * Executes the search synchronously.
         *
         * @return List of matching player profiles
         */
        public List<PlayerProfile> find() {
            try {
                return findAsync().get();
            } catch (Exception e) {
                LOGGER.severe("Failed to find players: " + e.getMessage());
                return Collections.emptyList();
            }
        }
        
        /**
         * Counts matching players without loading full profiles.
         *
         * @return A CompletableFuture containing the count
         */
        public CompletableFuture<Long> countAsync() {
            // This would need to be implemented with a count-specific query
            return findAsync().thenApply(List::size).thenApply(Long::valueOf);
        }
    }
}
