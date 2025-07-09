package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.dirty.DirtyDataEntry;
import sh.harold.fulcrum.api.data.dirty.DirtyDataManager;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages player data storage operations with integrated dirty data tracking support.
 */
public final class PlayerStorageManager {
    private static final Logger LOGGER = Logger.getLogger(PlayerStorageManager.class.getName());
    
    // Configuration for dirty data tracking
    private static boolean dirtyTrackingEnabled = true;
    private static Duration persistenceInterval = Duration.ofMinutes(5);
    private static boolean eventBasedPersistence = true;
    private static boolean timeBasedPersistence = true;
    
    // Internal state for persistence management
    private static ScheduledExecutorService persistenceExecutor;
    private static final Map<UUID, Long> lastPersistenceTime = new ConcurrentHashMap<>();
    private static final Object configurationLock = new Object();
    
    private PlayerStorageManager() {
    }
    
    /**
     * Initializes the storage manager with persistence configuration.
     *
     * @param config Configuration for dirty data persistence
     */
    public static void initialize(StorageManagerConfig config) {
        synchronized (configurationLock) {
            dirtyTrackingEnabled = config.dirtyTrackingEnabled;
            persistenceInterval = config.persistenceInterval;
            eventBasedPersistence = config.eventBasedPersistence;
            timeBasedPersistence = config.timeBasedPersistence;
            
            if (timeBasedPersistence && persistenceExecutor == null) {
                startTimedPersistence();
            }
            
            LOGGER.info("PlayerStorageManager initialized with dirty tracking: " + dirtyTrackingEnabled);
        }
    }
    
    /**
     * Shuts down the storage manager and cleans up resources.
     */
    public static void shutdown() {
        synchronized (configurationLock) {
            if (persistenceExecutor != null) {
                persistenceExecutor.shutdown();
                try {
                    if (!persistenceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        persistenceExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    persistenceExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                persistenceExecutor = null;
            }
            lastPersistenceTime.clear();
        }
    }

    public static <T> T load(UUID playerId, PlayerDataSchema<T> schema) {
        var backend = PlayerDataRegistry.getBackend(schema);
        if (backend == null) throw new IllegalStateException("No backend registered for schema: " + schema.schemaKey());
        return backend.load(playerId, schema);
    }

    public static <T> void save(UUID playerId, PlayerDataSchema<T> schema, T data) {
        var backend = PlayerDataRegistry.getBackend(schema);
        if (backend == null) throw new IllegalStateException("No backend registered for schema: " + schema.schemaKey());
        backend.save(playerId, schema, data);
    }

    public static <T> T loadOrCreate(UUID playerId, PlayerDataSchema<T> schema) {
        var backend = PlayerDataRegistry.getBackend(schema);
        if (backend == null) throw new IllegalStateException("No backend registered for schema: " + schema.schemaKey());
        return backend.loadOrCreate(playerId, schema);
    }
    
    /**
     * Saves data and marks it as dirty if dirty tracking is enabled.
     * This method integrates with the dirty data system for deferred persistence.
     *
     * @param playerId The player ID
     * @param schema The data schema
     * @param data The data to save
     * @param immediate Whether to save immediately or defer to dirty tracking
     */
    public static <T> void saveWithDirtyTracking(UUID playerId, PlayerDataSchema<T> schema, T data, boolean immediate) {
        if (!dirtyTrackingEnabled || immediate) {
            // Save directly to backend
            save(playerId, schema, data);
            return;
        }
        
        // Mark as dirty for deferred persistence
        if (DirtyDataManager.isInitialized()) {
            DirtyDataManager.markDirty(playerId, schema.schemaKey(), data, DirtyDataEntry.ChangeType.UPDATE);
            
            // Trigger event-based persistence if enabled
            if (eventBasedPersistence) {
                triggerEventBasedPersistence(playerId);
            }
        } else {
            // Fallback to immediate save if dirty tracking is not available
            save(playerId, schema, data);
        }
    }
    
    /**
     * Saves only dirty data for the specified player.
     *
     * @param playerId The player ID
     * @return The number of dirty entries persisted
     */
    public static int saveDirtyData(UUID playerId) {
        if (!dirtyTrackingEnabled || !DirtyDataManager.isInitialized()) {
            return 0;
        }
        
        int persistedCount = DirtyDataManager.persistDirtyData(playerId);
        if (persistedCount > 0) {
            lastPersistenceTime.put(playerId, System.currentTimeMillis());
        }
        return persistedCount;
    }
    
    /**
     * Asynchronously saves only dirty data for the specified player.
     *
     * @param playerId The player ID
     * @return A CompletableFuture containing the number of dirty entries persisted
     */
    public static CompletableFuture<Integer> saveDirtyDataAsync(UUID playerId) {
        if (!dirtyTrackingEnabled || !DirtyDataManager.isInitialized()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return DirtyDataManager.persistDirtyDataAsync(playerId)
                .thenApply(count -> {
                    if (count > 0) {
                        lastPersistenceTime.put(playerId, System.currentTimeMillis());
                    }
                    return count;
                });
    }
    
    /**
     * Saves all dirty data across all players.
     *
     * @return The number of dirty entries persisted
     */
    public static int saveAllDirtyData() {
        if (!dirtyTrackingEnabled || !DirtyDataManager.isInitialized()) {
            return 0;
        }
        
        return DirtyDataManager.persistAllDirtyData();
    }
    
    /**
     * Asynchronously saves all dirty data across all players.
     *
     * @return A CompletableFuture containing the number of dirty entries persisted
     */
    public static CompletableFuture<Integer> saveAllDirtyDataAsync() {
        if (!dirtyTrackingEnabled || !DirtyDataManager.isInitialized()) {
            return CompletableFuture.completedFuture(0);
        }
        
        return DirtyDataManager.persistAllDirtyDataAsync();
    }
    
    /**
     * Checks if a player has dirty data.
     *
     * @param playerId The player ID
     * @return true if the player has dirty data, false otherwise
     */
    public static boolean hasDirtyData(UUID playerId) {
        if (!dirtyTrackingEnabled || !DirtyDataManager.isInitialized()) {
            return false;
        }
        
        return DirtyDataManager.getCache().isDirty(playerId);
    }
    
    /**
     * Gets the count of dirty data entries for a player.
     *
     * @param playerId The player ID
     * @return The count of dirty data entries
     */
    public static int getDirtyDataCount(UUID playerId) {
        if (!dirtyTrackingEnabled || !DirtyDataManager.isInitialized()) {
            return 0;
        }
        
        return DirtyDataManager.getCache().getDirtyCount(playerId);
    }
    
    /**
     * Configures the dirty data persistence interval.
     *
     * @param interval The interval between persistence operations
     */
    public static void setPersistenceInterval(Duration interval) {
        synchronized (configurationLock) {
            persistenceInterval = interval;
            if (timeBasedPersistence && persistenceExecutor != null) {
                // Restart with new interval
                persistenceExecutor.shutdown();
                startTimedPersistence();
            }
        }
    }
    
    /**
     * Enables or disables dirty data tracking.
     *
     * @param enabled Whether to enable dirty data tracking
     */
    public static void setDirtyTrackingEnabled(boolean enabled) {
        synchronized (configurationLock) {
            dirtyTrackingEnabled = enabled;
            LOGGER.info("Dirty data tracking " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Enables or disables event-based persistence.
     *
     * @param enabled Whether to enable event-based persistence
     */
    public static void setEventBasedPersistence(boolean enabled) {
        synchronized (configurationLock) {
            eventBasedPersistence = enabled;
            LOGGER.info("Event-based persistence " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Enables or disables time-based persistence.
     *
     * @param enabled Whether to enable time-based persistence
     */
    public static void setTimeBasedPersistence(boolean enabled) {
        synchronized (configurationLock) {
            timeBasedPersistence = enabled;
            if (enabled && persistenceExecutor == null) {
                startTimedPersistence();
            } else if (!enabled && persistenceExecutor != null) {
                persistenceExecutor.shutdown();
                persistenceExecutor = null;
            }
            LOGGER.info("Time-based persistence " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Triggers event-based persistence for a specific player.
     *
     * @param playerId The player ID
     */
    private static void triggerEventBasedPersistence(UUID playerId) {
        Long lastTime = lastPersistenceTime.get(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Only trigger if enough time has passed since last persistence
        if (lastTime == null || (currentTime - lastTime) > persistenceInterval.toMillis() / 2) {
            CompletableFuture.runAsync(() -> {
                try {
                    saveDirtyData(playerId);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Event-based persistence failed for player " + playerId, e);
                }
            });
        }
    }
    
    /**
     * Starts the time-based persistence task.
     */
    private static void startTimedPersistence() {
        if (persistenceExecutor != null) {
            return;
        }
        
        persistenceExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "PlayerStorageManager-Persistence");
            t.setDaemon(true);
            return t;
        });
        
        persistenceExecutor.scheduleAtFixedRate(() -> {
            try {
                int savedCount = saveAllDirtyData();
                if (savedCount > 0) {
                    LOGGER.log(Level.INFO, "Time-based persistence completed: {0} entries saved", savedCount);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Time-based persistence failed", e);
            }
        }, persistenceInterval.toMillis(), persistenceInterval.toMillis(), TimeUnit.MILLISECONDS);
        
        LOGGER.info("Started time-based persistence with interval: " + persistenceInterval);
    }
    
    /**
     * Configuration class for storage manager.
     */
    public static class StorageManagerConfig {
        public boolean dirtyTrackingEnabled = true;
        public Duration persistenceInterval = Duration.ofMinutes(5);
        public boolean eventBasedPersistence = true;
        public boolean timeBasedPersistence = true;
        
        public StorageManagerConfig() {}
        
        public StorageManagerConfig(boolean dirtyTrackingEnabled, Duration persistenceInterval,
                                   boolean eventBasedPersistence, boolean timeBasedPersistence) {
            this.dirtyTrackingEnabled = dirtyTrackingEnabled;
            this.persistenceInterval = persistenceInterval;
            this.eventBasedPersistence = eventBasedPersistence;
            this.timeBasedPersistence = timeBasedPersistence;
        }
    }
}
