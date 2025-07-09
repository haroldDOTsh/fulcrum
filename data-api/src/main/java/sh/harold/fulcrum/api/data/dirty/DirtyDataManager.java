package sh.harold.fulcrum.api.data.dirty;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.data.registry.PlayerStorageManager;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages dirty data lifecycle and persistence triggers.
 * 
 * This class provides high-level operations for managing dirty data across the system,
 * including batch operations, automatic persistence triggers, and cleanup operations.
 */
public final class DirtyDataManager {
    
    private static final Logger LOGGER = Logger.getLogger(DirtyDataManager.class.getName());
    
    private static DirtyDataCache dirtyDataCache;
    private static ScheduledExecutorService scheduledExecutor;
    private static final Object lock = new Object();
    
    // Configuration
    private static Duration autoPersistInterval = Duration.ofMinutes(5);
    private static Duration entryMaxAge = Duration.ofHours(1);
    private static int batchSize = 100;
    private static boolean autoCleanupEnabled = true;
    
    private DirtyDataManager() {
        // Utility class
    }
    
    /**
     * Initializes the dirty data manager with the specified cache implementation.
     * 
     * @param cache The dirty data cache implementation to use
     * @throws IllegalArgumentException if cache is null
     * @throws IllegalStateException if already initialized
     */
    public static void initialize(DirtyDataCache cache) {
        if (cache == null) {
            throw new IllegalArgumentException("cache cannot be null");
        }
        
        synchronized (lock) {
            if (dirtyDataCache != null) {
                throw new IllegalStateException("DirtyDataManager is already initialized");
            }
            
            dirtyDataCache = cache;
            scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "DirtyDataManager-Worker");
                t.setDaemon(true);
                return t;
            });
            
            // Start automatic persistence if enabled
            if (autoPersistInterval.toMillis() > 0) {
                startAutomaticPersistence();
            }
            
            // Start cleanup task if enabled
            if (autoCleanupEnabled) {
                startCleanupTask();
            }
            
            LOGGER.info("DirtyDataManager initialized with cache: " + cache.getClass().getSimpleName());
        }
    }
    
    /**
     * Shuts down the dirty data manager and cleans up resources.
     */
    public static void shutdown() {
        synchronized (lock) {
            if (scheduledExecutor != null) {
                scheduledExecutor.shutdown();
                try {
                    if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduledExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduledExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                scheduledExecutor = null;
            }
            
            dirtyDataCache = null;
            LOGGER.info("DirtyDataManager shut down");
        }
    }
    
    /**
     * Checks if the manager is initialized.
     * 
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return dirtyDataCache != null;
    }
    
    /**
     * Gets the current dirty data cache instance.
     * 
     * @return The dirty data cache
     * @throws IllegalStateException if not initialized
     */
    public static DirtyDataCache getCache() {
        ensureInitialized();
        return dirtyDataCache;
    }
    
    /**
     * Marks data as dirty for the specified player and schema.
     * 
     * @param playerId The player ID
     * @param schemaKey The schema key
     * @param data The data object
     * @param changeType The change type
     * @throws IllegalStateException if not initialized
     */
    public static void markDirty(UUID playerId, String schemaKey, Object data, DirtyDataEntry.ChangeType changeType) {
        ensureInitialized();
        
        try {
            dirtyDataCache.markDirty(playerId, schemaKey, data, changeType);
            LOGGER.log(Level.FINE, "Marked data as dirty for player {0}, schema {1}, change type {2}", 
                      new Object[]{playerId, schemaKey, changeType});
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to mark data as dirty", e);
        }
    }
    
    /**
     * Persists all dirty data for the specified player.
     * 
     * @param playerId The player ID
     * @return The number of entries persisted
     * @throws IllegalStateException if not initialized
     */
    public static int persistDirtyData(UUID playerId) {
        ensureInitialized();
        
        try {
            Collection<DirtyDataEntry> entries = dirtyDataCache.getDirtyEntries(playerId);
            int persistedCount = 0;
            
            for (DirtyDataEntry entry : entries) {
                if (persistEntry(entry)) {
                    dirtyDataCache.clearDirty(entry.getPlayerId(), entry.getSchemaKey());
                    persistedCount++;
                }
            }
            
            if (persistedCount > 0) {
                LOGGER.log(Level.INFO, "Persisted {0} dirty data entries for player {1}", 
                          new Object[]{persistedCount, playerId});
            }
            
            return persistedCount;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to persist dirty data for player " + playerId, e);
            return 0;
        }
    }
    
    /**
     * Persists all dirty data across all players.
     * 
     * @return The number of entries persisted
     * @throws IllegalStateException if not initialized
     */
    public static int persistAllDirtyData() {
        ensureInitialized();
        
        try {
            Collection<DirtyDataEntry> entries = dirtyDataCache.getAllDirtyEntries();
            return persistEntries(entries);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to persist all dirty data", e);
            return 0;
        }
    }
    
    /**
     * Persists dirty data entries in batches.
     * 
     * @param entries The entries to persist
     * @return The number of entries persisted
     */
    public static int persistEntries(Collection<DirtyDataEntry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }
        
        int persistedCount = 0;
        List<DirtyDataEntry> batch = new ArrayList<>();
        
        for (DirtyDataEntry entry : entries) {
            batch.add(entry);
            
            if (batch.size() >= batchSize) {
                persistedCount += processBatch(batch);
                batch.clear();
            }
        }
        
        if (!batch.isEmpty()) {
            persistedCount += processBatch(batch);
        }
        
        return persistedCount;
    }
    
    /**
     * Asynchronously persists all dirty data for the specified player.
     * 
     * @param playerId The player ID
     * @return A CompletableFuture containing the number of entries persisted
     * @throws IllegalStateException if not initialized
     */
    public static CompletableFuture<Integer> persistDirtyDataAsync(UUID playerId) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> persistDirtyData(playerId));
    }
    
    /**
     * Asynchronously persists all dirty data across all players.
     * 
     * @return A CompletableFuture containing the number of entries persisted
     * @throws IllegalStateException if not initialized
     */
    public static CompletableFuture<Integer> persistAllDirtyDataAsync() {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> persistAllDirtyData());
    }
    
    /**
     * Cleans up old dirty data entries based on the configured maximum age.
     * 
     * @return The number of entries cleaned up
     * @throws IllegalStateException if not initialized
     */
    public static int cleanupOldEntries() {
        ensureInitialized();
        
        try {
            Instant threshold = Instant.now().minus(entryMaxAge);
            Collection<DirtyDataEntry> oldEntries = dirtyDataCache.getDirtyEntriesOlderThan(threshold);
            
            int cleanedCount = 0;
            for (DirtyDataEntry entry : oldEntries) {
                // Try to persist old entries before cleaning them up
                if (persistEntry(entry)) {
                    dirtyDataCache.clearDirty(entry.getPlayerId(), entry.getSchemaKey());
                    cleanedCount++;
                } else {
                    // If persistence fails, still clean up to prevent memory leaks
                    dirtyDataCache.clearDirty(entry.getPlayerId(), entry.getSchemaKey());
                    cleanedCount++;
                    LOGGER.log(Level.WARNING, "Failed to persist old dirty data entry, cleaned up anyway: {0}", entry);
                }
            }
            
            if (cleanedCount > 0) {
                LOGGER.log(Level.INFO, "Cleaned up {0} old dirty data entries", cleanedCount);
            }
            
            return cleanedCount;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to cleanup old dirty data entries", e);
            return 0;
        }
    }
    
    /**
     * Gets statistics about the current dirty data state.
     * 
     * @return Statistics about dirty data
     * @throws IllegalStateException if not initialized
     */
    public static DirtyDataStats getStats() {
        ensureInitialized();
        
        int totalCount = dirtyDataCache.getTotalDirtyCount();
        Collection<DirtyDataEntry> allEntries = dirtyDataCache.getAllDirtyEntries();
        
        Map<String, Integer> schemaCountMap = new HashMap<>();
        Map<DirtyDataEntry.ChangeType, Integer> changeTypeCountMap = new HashMap<>();
        
        for (DirtyDataEntry entry : allEntries) {
            schemaCountMap.merge(entry.getSchemaKey(), 1, Integer::sum);
            changeTypeCountMap.merge(entry.getChangeType(), 1, Integer::sum);
        }
        
        return new DirtyDataStats(totalCount, schemaCountMap, changeTypeCountMap);
    }
    
    /**
     * Configures the automatic persistence interval.
     * 
     * @param interval The interval between automatic persistence runs
     */
    public static void setAutoPersistInterval(Duration interval) {
        autoPersistInterval = interval;
    }
    
    /**
     * Configures the maximum age for dirty data entries.
     * 
     * @param maxAge The maximum age before entries are cleaned up
     */
    public static void setEntryMaxAge(Duration maxAge) {
        entryMaxAge = maxAge;
    }
    
    /**
     * Configures the batch size for persistence operations.
     * 
     * @param size The batch size
     */
    public static void setBatchSize(int size) {
        batchSize = Math.max(1, size);
    }
    
    /**
     * Configures whether automatic cleanup is enabled.
     * 
     * @param enabled Whether to enable automatic cleanup
     */
    public static void setAutoCleanupEnabled(boolean enabled) {
        autoCleanupEnabled = enabled;
    }
    
    /**
     * Starts the automatic persistence task.
     */
    private static void startAutomaticPersistence() {
        if (scheduledExecutor == null) {
            return;
        }
        
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                int persistedCount = persistAllDirtyData();
                if (persistedCount > 0) {
                    LOGGER.log(Level.INFO, "Automatic persistence completed: {0} entries persisted", persistedCount);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Automatic persistence failed", e);
            }
        }, autoPersistInterval.toMillis(), autoPersistInterval.toMillis(), TimeUnit.MILLISECONDS);
        
        LOGGER.info("Started automatic persistence with interval: " + autoPersistInterval);
    }
    
    /**
     * Starts the cleanup task.
     */
    private static void startCleanupTask() {
        if (scheduledExecutor == null) {
            return;
        }
        
        // Run cleanup every hour
        scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                dirtyDataCache.cleanup();
                int cleanedCount = cleanupOldEntries();
                if (cleanedCount > 0) {
                    LOGGER.log(Level.INFO, "Automatic cleanup completed: {0} entries cleaned", cleanedCount);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Automatic cleanup failed", e);
            }
        }, 3600000, 3600000, TimeUnit.MILLISECONDS); // 1 hour
        
        LOGGER.info("Started automatic cleanup task");
    }
    
    /**
     * Processes a batch of dirty data entries.
     * 
     * @param batch The batch to process
     * @return The number of entries successfully processed
     */
    private static int processBatch(List<DirtyDataEntry> batch) {
        int processedCount = 0;
        
        for (DirtyDataEntry entry : batch) {
            try {
                if (persistEntry(entry)) {
                    dirtyDataCache.clearDirty(entry.getPlayerId(), entry.getSchemaKey());
                    processedCount++;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to process dirty data entry: " + entry, e);
            }
        }
        
        return processedCount;
    }
    
    /**
     * Persists a single dirty data entry.
     * 
     * @param entry The entry to persist
     * @return true if successfully persisted, false otherwise
     */
    private static boolean persistEntry(DirtyDataEntry entry) {
        try {
            // Skip DELETE operations that don't have data
            if (entry.getChangeType() == DirtyDataEntry.ChangeType.DELETE && entry.getData() == null) {
                return true;
            }
            
            // Find the schema for this entry
            PlayerDataSchema<?> schema = null;
            for (PlayerDataSchema<?> registeredSchema : PlayerDataRegistry.allSchemas()) {
                if (registeredSchema.schemaKey().equals(entry.getSchemaKey())) {
                    schema = registeredSchema;
                    break;
                }
            }
            
            if (schema == null) {
                LOGGER.log(Level.WARNING, "No schema found for dirty data entry: {0}", entry.getSchemaKey());
                return false;
            }
            
            // Cast and save the data
            @SuppressWarnings("unchecked")
            PlayerDataSchema<Object> typedSchema = (PlayerDataSchema<Object>) schema;
            
            if (entry.getData() != null) {
                PlayerStorageManager.save(entry.getPlayerId(), typedSchema, entry.getData());
            }
            
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to persist dirty data entry: " + entry, e);
            return false;
        }
    }
    
    /**
     * Ensures the manager is initialized.
     * 
     * @throws IllegalStateException if not initialized
     */
    private static void ensureInitialized() {
        if (dirtyDataCache == null) {
            throw new IllegalStateException("DirtyDataManager is not initialized. Call initialize() first.");
        }
    }
    
    /**
     * Statistics about dirty data.
     */
    public static class DirtyDataStats {
        private final int totalCount;
        private final Map<String, Integer> schemaCountMap;
        private final Map<DirtyDataEntry.ChangeType, Integer> changeTypeCountMap;
        
        public DirtyDataStats(int totalCount, Map<String, Integer> schemaCountMap, 
                             Map<DirtyDataEntry.ChangeType, Integer> changeTypeCountMap) {
            this.totalCount = totalCount;
            this.schemaCountMap = Collections.unmodifiableMap(new HashMap<>(schemaCountMap));
            this.changeTypeCountMap = Collections.unmodifiableMap(new HashMap<>(changeTypeCountMap));
        }
        
        public int getTotalCount() {
            return totalCount;
        }
        
        public Map<String, Integer> getSchemaCountMap() {
            return schemaCountMap;
        }
        
        public Map<DirtyDataEntry.ChangeType, Integer> getChangeTypeCountMap() {
            return changeTypeCountMap;
        }
        
        @Override
        public String toString() {
            return "DirtyDataStats{" +
                   "totalCount=" + totalCount +
                   ", schemaCountMap=" + schemaCountMap +
                   ", changeTypeCountMap=" + changeTypeCountMap +
                   '}';
        }
    }
}