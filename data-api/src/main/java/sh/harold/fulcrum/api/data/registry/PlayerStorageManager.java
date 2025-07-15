package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.dirty.DirtyDataEntry;
import sh.harold.fulcrum.api.data.dirty.DirtyDataManager;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlayerStorageManager {
    private static final Logger LOGGER = Logger.getLogger(PlayerStorageManager.class.getName());
    private static final Map<UUID, Long> lastPersistenceTime = new ConcurrentHashMap<>();
    private static final Object configurationLock = new Object();
    // Configuration for dirty data tracking
    private static boolean dirtyTrackingEnabled = true;
    private static Duration persistenceInterval = Duration.ofMinutes(5);
    private static boolean eventBasedPersistence = true;
    private static boolean timeBasedPersistence = true;
    // Internal state for persistence management
    private static ScheduledExecutorService persistenceExecutor;

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

            LOGGER.info("PlayerStorageManager initialized with dirty tracking: " + dirtyTrackingEnabled +
                       ", time-based persistence: " + timeBasedPersistence +
                       ", event-based persistence: " + eventBasedPersistence);
            
            // Ensure unified save path: if we handle time-based persistence,
            // DirtyDataManager should not run its own automatic persistence
            if (timeBasedPersistence && DirtyDataManager.isInitialized()) {
                LOGGER.info("PlayerStorageManager handling time-based persistence - coordinating with DirtyDataManager");
            }
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
        LOGGER.log(Level.INFO, "[DEBUG] PlayerStorageManager.save called: playerId={0}, schema={1}, data={2}",
            new Object[]{playerId, schema.schemaKey(), data.getClass().getSimpleName()});
        
        var backend = PlayerDataRegistry.getBackend(schema);
        if (backend == null) {
            LOGGER.log(Level.SEVERE, "[DEBUG] BACKEND NOT FOUND for schema: {0}", schema.schemaKey());
            throw new IllegalStateException("No backend registered for schema: " + schema.schemaKey());
        }
        
        LOGGER.log(Level.INFO, "[DEBUG] Using backend: {0} for schema: {1}",
            new Object[]{backend.getClass().getSimpleName(), schema.schemaKey()});
        
        backend.save(playerId, schema, data);
        LOGGER.log(Level.INFO, "[DEBUG] PlayerStorageManager.save completed successfully for playerId={0}", playerId);
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
     * @param playerId  The player ID
     * @param schema    The data schema
     * @param data      The data to save
     * @param immediate Whether to save immediately or defer to dirty tracking
     */
    public static <T> void saveWithDirtyTracking(UUID playerId, PlayerDataSchema<T> schema, T data, boolean immediate) {
        LOGGER.info("[DIAGNOSTIC] PlayerStorageManager.saveWithDirtyTracking() called for player: " + playerId +
                   ", schema: " + schema.schemaKey() + ", immediate: " + immediate);
        
        LOGGER.info("[DIAGNOSTIC] Configuration state - dirtyTrackingEnabled: " + dirtyTrackingEnabled +
                   ", eventBasedPersistence: " + eventBasedPersistence);
        
        if (!dirtyTrackingEnabled || immediate) {
            LOGGER.info("[DIAGNOSTIC] Taking immediate save path (dirtyTrackingEnabled: " + dirtyTrackingEnabled +
                       ", immediate: " + immediate + ")");
            try {
                // Save directly to backend
                save(playerId, schema, data);
                LOGGER.info("[DIAGNOSTIC] Immediate save completed successfully for player: " + playerId);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DIAGNOSTIC] Immediate save failed for player " + playerId + ", schema: " + schema.schemaKey(), e);
                throw new RuntimeException("Failed to save data immediately for player " + playerId, e);
            }
            return;
        }

        // Mark as dirty for deferred persistence
        boolean dirtyManagerInitialized = DirtyDataManager.isInitialized();
        LOGGER.info("[DIAGNOSTIC] DirtyDataManager.isInitialized(): " + dirtyManagerInitialized);
        
        if (dirtyManagerInitialized) {
            try {
                LOGGER.info("[DIAGNOSTIC] Calling DirtyDataManager.markDirty() with schema key: " + schema.schemaKey());
                DirtyDataManager.markDirty(playerId, schema.schemaKey(), data, DirtyDataEntry.ChangeType.UPDATE);
                LOGGER.info("[DIAGNOSTIC] DirtyDataManager.markDirty() call completed successfully");

                // Trigger event-based persistence if enabled
                if (eventBasedPersistence) {
                    LOGGER.info("[DIAGNOSTIC] Triggering event-based persistence for player: " + playerId);
                    triggerEventBasedPersistence(playerId);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DIAGNOSTIC] Failed to mark data as dirty for player " + playerId + ", schema: " + schema.schemaKey(), e);
                // Fallback to immediate save if dirty tracking fails
                LOGGER.warning("[DIAGNOSTIC] Falling back to immediate save due to dirty tracking failure");
                try {
                    save(playerId, schema, data);
                } catch (Exception saveException) {
                    LOGGER.log(Level.SEVERE, "[DIAGNOSTIC] Fallback immediate save also failed for player " + playerId, saveException);
                    throw new RuntimeException("Both dirty tracking and immediate save failed for player " + playerId, saveException);
                }
            }
        } else {
            LOGGER.warning("[DIAGNOSTIC] DirtyDataManager not initialized - falling back to immediate save");
            try {
                // Fallback to immediate save if dirty tracking is not available
                save(playerId, schema, data);
                LOGGER.info("[DIAGNOSTIC] Fallback immediate save completed successfully for player: " + playerId);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[DIAGNOSTIC] Fallback immediate save failed for player " + playerId, e);
                throw new RuntimeException("Failed to save data (dirty tracking unavailable) for player " + playerId, e);
            }
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

        // Use a more reasonable throttling interval (30 seconds instead of 2.5 minutes)
        // This prevents blocking important saves during logout while still preventing spam
        long throttleInterval = Math.min(30000, persistenceInterval.toMillis() / 10);
        
        if (lastTime == null || (currentTime - lastTime) > throttleInterval) {
            CompletableFuture.runAsync(() -> {
                try {
                    int savedCount = saveDirtyData(playerId);
                    if (savedCount > 0) {
                        LOGGER.log(Level.INFO, "Event-based persistence completed: {0} entries saved for player {1}",
                                new Object[]{savedCount, playerId});
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Event-based persistence failed for player " + playerId, e);
                    // Attempt immediate retry for critical failures
                    try {
                        LOGGER.log(Level.INFO, "Attempting immediate retry for player " + playerId);
                        saveDirtyData(playerId);
                    } catch (Exception retryException) {
                        LOGGER.log(Level.SEVERE, "Event-based persistence retry failed for player " + playerId, retryException);
                    }
                }
            });
        } else {
            LOGGER.log(Level.FINE, "Event-based persistence throttled for player {0} (last save: {1}ms ago)",
                    new Object[]{playerId, currentTime - lastTime});
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


    public static class StorageManagerConfig {
        public boolean dirtyTrackingEnabled = true;
        public Duration persistenceInterval = Duration.ofMinutes(5);
        public boolean eventBasedPersistence = true;
        public boolean timeBasedPersistence = true;

        public StorageManagerConfig() {
        }

        public StorageManagerConfig(boolean dirtyTrackingEnabled, Duration persistenceInterval,
                                    boolean eventBasedPersistence, boolean timeBasedPersistence) {
            this.dirtyTrackingEnabled = dirtyTrackingEnabled;
            this.persistenceInterval = persistenceInterval;
            this.eventBasedPersistence = eventBasedPersistence;
            this.timeBasedPersistence = timeBasedPersistence;
        }
    }
}
