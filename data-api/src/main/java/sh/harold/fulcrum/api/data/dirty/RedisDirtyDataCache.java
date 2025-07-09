package sh.harold.fulcrum.api.data.dirty;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Redis-based implementation of DirtyDataCache for distributed environments.
 * 
 * This implementation uses Redis operations for distributed caching and provides
 * persistence across multiple application instances. It handles serialization
 * and deserialization of dirty data entries automatically.
 * 
 * Note: This implementation requires a Redis client dependency to be available.
 * The actual Redis operations are abstracted through the RedisOperations interface
 * to allow for different Redis client implementations.
 */
public class RedisDirtyDataCache implements DirtyDataCache {
    
    private static final Logger LOGGER = Logger.getLogger(RedisDirtyDataCache.class.getName());
    private static final String DIRTY_DATA_PREFIX = "fulcrum:dirty:";
    private static final String DIRTY_PLAYERS_SET = "fulcrum:dirty_players";
    private static final String ENTRY_SEPARATOR = "|";
    
    private final RedisOperations redisOperations;
    private final long entryTtlSeconds;
    
    /**
     * Interface for Redis operations to abstract different Redis client implementations.
     */
    public interface RedisOperations {
        /**
         * Sets a key-value pair in Redis with optional TTL.
         * 
         * @param key The key
         * @param value The value
         * @param ttlSeconds TTL in seconds, or -1 for no expiration
         */
        void set(String key, String value, long ttlSeconds);
        
        /**
         * Gets a value from Redis.
         * 
         * @param key The key
         * @return The value, or null if not found
         */
        String get(String key);
        
        /**
         * Deletes a key from Redis.
         * 
         * @param key The key to delete
         * @return true if the key was deleted, false if it didn't exist
         */
        boolean delete(String key);
        
        /**
         * Adds a member to a Redis set.
         * 
         * @param setKey The set key
         * @param member The member to add
         */
        void sAdd(String setKey, String member);
        
        /**
         * Removes a member from a Redis set.
         * 
         * @param setKey The set key
         * @param member The member to remove
         */
        void sRem(String setKey, String member);
        
        /**
         * Gets all members of a Redis set.
         * 
         * @param setKey The set key
         * @return Set of members
         */
        Set<String> sMembers(String setKey);
        
        /**
         * Checks if a member exists in a Redis set.
         * 
         * @param setKey The set key
         * @param member The member to check
         * @return true if the member exists
         */
        boolean sIsMember(String setKey, String member);
        
        /**
         * Gets keys matching a pattern.
         * 
         * @param pattern The pattern
         * @return Set of matching keys
         */
        Set<String> keys(String pattern);
        
        /**
         * Deletes multiple keys.
         * 
         * @param keys The keys to delete
         * @return Number of keys deleted
         */
        long delete(String... keys);
        
        /**
         * Checks if Redis is available.
         * 
         * @return true if Redis is available
         */
        boolean isAvailable();
    }
    
    /**
     * Creates a new Redis dirty data cache.
     * 
     * @param redisOperations The Redis operations implementation
     * @param entryTtlSeconds TTL for entries in seconds, or -1 for no expiration
     */
    public RedisDirtyDataCache(RedisOperations redisOperations, long entryTtlSeconds) {
        this.redisOperations = Objects.requireNonNull(redisOperations, "redisOperations cannot be null");
        this.entryTtlSeconds = entryTtlSeconds;
    }
    
    /**
     * Creates a new Redis dirty data cache with default TTL of 1 hour.
     * 
     * @param redisOperations The Redis operations implementation
     */
    public RedisDirtyDataCache(RedisOperations redisOperations) {
        this(redisOperations, 3600); // 1 hour default TTL
    }
    
    @Override
    public void markDirty(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType) {
        validateParameters(playerId, schemaKey, changeType);
        
        if (!redisOperations.isAvailable()) {
            LOGGER.warning("Redis is not available, cannot mark data as dirty");
            return;
        }
        
        try {
            DirtyDataEntry entry = new DirtyDataEntry(playerId, schemaKey, data, changeType);
            String key = createRedisKey(playerId, schemaKey);
            String serializedEntry = serializeEntry(entry);
            
            redisOperations.set(key, serializedEntry, entryTtlSeconds);
            redisOperations.sAdd(DIRTY_PLAYERS_SET, playerId.toString());
            
            LOGGER.log(Level.FINE, "Marked data as dirty for player {0}, schema {1}", 
                      new Object[]{playerId, schemaKey});
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to mark data as dirty in Redis", e);
        }
    }
    
    @Override
    public boolean isDirty(UUID playerId, String schemaKey) {
        validateParameters(playerId, schemaKey);
        
        if (!redisOperations.isAvailable()) {
            return false;
        }
        
        try {
            String key = createRedisKey(playerId, schemaKey);
            return redisOperations.get(key) != null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check if data is dirty in Redis", e);
            return false;
        }
    }
    
    @Override
    public boolean isDirty(UUID playerId) {
        validatePlayerId(playerId);
        
        if (!redisOperations.isAvailable()) {
            return false;
        }
        
        try {
            return redisOperations.sIsMember(DIRTY_PLAYERS_SET, playerId.toString());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check if player has dirty data in Redis", e);
            return false;
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getDirtyEntries(UUID playerId) {
        validatePlayerId(playerId);
        
        if (!redisOperations.isAvailable()) {
            return Collections.emptyList();
        }
        
        try {
            String pattern = DIRTY_DATA_PREFIX + playerId + ":*";
            Set<String> keys = redisOperations.keys(pattern);
            
            List<DirtyDataEntry> entries = new ArrayList<>();
            for (String key : keys) {
                String serializedEntry = redisOperations.get(key);
                if (serializedEntry != null) {
                    DirtyDataEntry entry = deserializeEntry(serializedEntry);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            
            return entries;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get dirty entries from Redis", e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getAllDirtyEntries() {
        if (!redisOperations.isAvailable()) {
            return Collections.emptyList();
        }
        
        try {
            Set<String> playerIds = redisOperations.sMembers(DIRTY_PLAYERS_SET);
            List<DirtyDataEntry> allEntries = new ArrayList<>();
            
            for (String playerId : playerIds) {
                UUID uuid = UUID.fromString(playerId);
                Collection<DirtyDataEntry> playerEntries = getDirtyEntries(uuid);
                allEntries.addAll(playerEntries);
            }
            
            return allEntries;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get all dirty entries from Redis", e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public Collection<DirtyDataEntry> getDirtyEntriesOlderThan(Instant threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException("threshold cannot be null");
        }
        
        return getAllDirtyEntries().stream()
                .filter(entry -> entry.isOlderThan(threshold))
                .collect(Collectors.toList());
    }
    
    @Override
    public void clearDirty(UUID playerId, String schemaKey) {
        validateParameters(playerId, schemaKey);
        
        if (!redisOperations.isAvailable()) {
            return;
        }
        
        try {
            String key = createRedisKey(playerId, schemaKey);
            redisOperations.delete(key);
            
            // Check if player has any other dirty entries
            if (getDirtyEntries(playerId).isEmpty()) {
                redisOperations.sRem(DIRTY_PLAYERS_SET, playerId.toString());
            }
            
            LOGGER.log(Level.FINE, "Cleared dirty flag for player {0}, schema {1}", 
                      new Object[]{playerId, schemaKey});
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to clear dirty flag in Redis", e);
        }
    }
    
    @Override
    public void clearDirty(UUID playerId) {
        validatePlayerId(playerId);
        
        if (!redisOperations.isAvailable()) {
            return;
        }
        
        try {
            String pattern = DIRTY_DATA_PREFIX + playerId + ":*";
            Set<String> keys = redisOperations.keys(pattern);
            
            if (!keys.isEmpty()) {
                redisOperations.delete(keys.toArray(new String[0]));
            }
            
            redisOperations.sRem(DIRTY_PLAYERS_SET, playerId.toString());
            
            LOGGER.log(Level.FINE, "Cleared all dirty flags for player {0}", playerId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to clear all dirty flags for player in Redis", e);
        }
    }
    
    @Override
    public void clearAllDirty() {
        if (!redisOperations.isAvailable()) {
            return;
        }
        
        try {
            Set<String> keys = redisOperations.keys(DIRTY_DATA_PREFIX + "*");
            if (!keys.isEmpty()) {
                redisOperations.delete(keys.toArray(new String[0]));
            }
            
            redisOperations.delete(DIRTY_PLAYERS_SET);
            
            LOGGER.info("Cleared all dirty flags from Redis");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to clear all dirty flags in Redis", e);
        }
    }
    
    @Override
    public int getDirtyCount(UUID playerId) {
        validatePlayerId(playerId);
        
        if (!redisOperations.isAvailable()) {
            return 0;
        }
        
        try {
            String pattern = DIRTY_DATA_PREFIX + playerId + ":*";
            Set<String> keys = redisOperations.keys(pattern);
            return keys.size();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get dirty count from Redis", e);
            return 0;
        }
    }
    
    @Override
    public int getTotalDirtyCount() {
        if (!redisOperations.isAvailable()) {
            return 0;
        }
        
        try {
            Set<String> keys = redisOperations.keys(DIRTY_DATA_PREFIX + "*");
            return keys.size();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get total dirty count from Redis", e);
            return 0;
        }
    }
    
    @Override
    public boolean supportsPersistence() {
        return true;
    }
    
    @Override
    public void cleanup() {
        // Redis TTL handles cleanup automatically
        // This method could be used for additional cleanup logic if needed
    }
    
    @Override
    public CompletableFuture<Void> markDirtyAsync(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType) {
        return CompletableFuture.runAsync(() -> markDirty(playerId, schemaKey, data, changeType));
    }
    
    @Override
    public CompletableFuture<Collection<DirtyDataEntry>> getDirtyEntriesAsync(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> getDirtyEntries(playerId));
    }
    
    @Override
    public CompletableFuture<Void> clearDirtyAsync(UUID playerId, String schemaKey) {
        return CompletableFuture.runAsync(() -> clearDirty(playerId, schemaKey));
    }
    
    /**
     * Creates a Redis key for the given player ID and schema key.
     * 
     * @param playerId The player ID
     * @param schemaKey The schema key
     * @return A Redis key string
     */
    private String createRedisKey(UUID playerId, String schemaKey) {
        return DIRTY_DATA_PREFIX + playerId + ":" + schemaKey;
    }
    
    /**
     * Serializes a DirtyDataEntry to a string for Redis storage.
     * 
     * @param entry The entry to serialize
     * @return Serialized string
     */
    private String serializeEntry(DirtyDataEntry entry) {
        // Simple pipe-separated format for basic serialization
        // In a real implementation, you might want to use JSON or a more robust format
        return entry.getPlayerId() + ENTRY_SEPARATOR +
               entry.getSchemaKey() + ENTRY_SEPARATOR +
               entry.getChangeType().name() + ENTRY_SEPARATOR +
               entry.getTimestamp().toEpochMilli() + ENTRY_SEPARATOR +
               (entry.getData() != null ? entry.getData().toString() : "");
    }
    
    /**
     * Deserializes a string from Redis storage to a DirtyDataEntry.
     * 
     * @param serialized The serialized string
     * @return The deserialized entry, or null if deserialization fails
     */
    private DirtyDataEntry deserializeEntry(String serialized) {
        try {
            String[] parts = serialized.split("\\|", 5);
            if (parts.length < 4) {
                return null;
            }
            
            UUID playerId = UUID.fromString(parts[0]);
            String schemaKey = parts[1];
            DirtyDataEntry.ChangeType changeType = DirtyDataEntry.ChangeType.valueOf(parts[2]);
            Instant timestamp = Instant.ofEpochMilli(Long.parseLong(parts[3]));
            Object data = parts.length > 4 && !parts[4].isEmpty() ? parts[4] : null;
            
            return new DirtyDataEntry(playerId, schemaKey, data, changeType, timestamp);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to deserialize dirty data entry: " + serialized, e);
            return null;
        }
    }
    
    /**
     * Validates the required parameters for operations involving player ID and schema key.
     * 
     * @param playerId The player ID to validate
     * @param schemaKey The schema key to validate
     * @throws IllegalArgumentException if any parameter is null
     */
    private void validateParameters(UUID playerId, String schemaKey) {
        validatePlayerId(playerId);
        if (schemaKey == null) {
            throw new IllegalArgumentException("schemaKey cannot be null");
        }
    }
    
    /**
     * Validates the required parameters for marking data as dirty.
     * 
     * @param playerId The player ID to validate
     * @param schemaKey The schema key to validate
     * @param changeType The change type to validate
     * @throws IllegalArgumentException if any parameter is null
     */
    private void validateParameters(UUID playerId, String schemaKey, DirtyDataEntry.ChangeType changeType) {
        validateParameters(playerId, schemaKey);
        if (changeType == null) {
            throw new IllegalArgumentException("changeType cannot be null");
        }
    }
    
    /**
     * Validates the player ID parameter.
     * 
     * @param playerId The player ID to validate
     * @throws IllegalArgumentException if playerId is null
     */
    private void validatePlayerId(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId cannot be null");
        }
    }
}