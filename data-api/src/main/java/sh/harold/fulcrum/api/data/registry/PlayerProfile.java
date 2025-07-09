package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.dirty.DirtyDataEntry;
import sh.harold.fulcrum.api.data.dirty.DirtyDataManager;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a player's data profile with integrated dirty data tracking support.
 * This class automatically tracks data modifications and can defer persistence using the dirty data system.
 */
public final class PlayerProfile {
    private static final Logger LOGGER = Logger.getLogger(PlayerProfile.class.getName());
    private static final Executor ASYNC_EXECUTOR = Executors.newCachedThreadPool();
    
    private final UUID playerId;
    private final Map<Class<?>, Object> data = new ConcurrentHashMap<>();
    private boolean isNew = true;
    private boolean dirtyTrackingEnabled = true;

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }
    
    /**
     * Constructs a PlayerProfile with configurable dirty tracking.
     *
     * @param playerId The player ID
     * @param dirtyTrackingEnabled Whether to enable dirty tracking for this profile
     */
    public PlayerProfile(UUID playerId, boolean dirtyTrackingEnabled) {
        this.playerId = playerId;
        this.dirtyTrackingEnabled = dirtyTrackingEnabled;
    }

    /**
     * Internal, synchronous. Requires prior load or safe context.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> schemaType) {
        if (data.containsKey(schemaType)) return (T) data.get(schemaType);
        var schema = PlayerDataRegistry.allSchemas().stream()
                .filter(s -> s.type().equals(schemaType))
                .findFirst().orElse(null);
        if (schema == null) throw new IllegalArgumentException("Schema not registered: " + schemaType);
        @SuppressWarnings("unchecked")
        PlayerDataSchema<T> typedSchema = (PlayerDataSchema<T>) schema;
        T loaded = PlayerStorageManager.loadOrCreate(playerId, typedSchema);
        if (loaded != null) {
            data.put(schemaType, loaded);
            isNew = false;
        }
        return loaded;
    }

    /**
     * Safe async version of get(...)
     */
    public <T> CompletableFuture<T> getAsync(Class<T> schemaType) {
        if (data.containsKey(schemaType)) {
            @SuppressWarnings("unchecked")
            T cached = (T) data.get(schemaType);
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            var schema = PlayerDataRegistry.allSchemas().stream()
                    .filter(s -> s.type().equals(schemaType))
                    .findFirst().orElse(null);
            if (schema == null) throw new IllegalArgumentException("Schema not registered: " + schemaType);
            @SuppressWarnings("unchecked")
            PlayerDataSchema<T> typedSchema = (PlayerDataSchema<T>) schema;
            T loaded = PlayerStorageManager.loadOrCreate(playerId, typedSchema);
            if (loaded != null) {
                data.put(schemaType, loaded);
                isNew = false;
            }
            return loaded;
        }, ASYNC_EXECUTOR);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> schemaType, Supplier<T> fallback) {
        if (data.containsKey(schemaType)) return (T) data.get(schemaType);
        var schema = PlayerDataRegistry.allSchemas().stream()
                .filter(s -> s.type().equals(schemaType))
                .findFirst().orElse(null);
        if (schema == null) return fallback.get();
        @SuppressWarnings("unchecked")
        PlayerDataSchema<T> typedSchema = (PlayerDataSchema<T>) schema;
        T loaded = PlayerStorageManager.loadOrCreate(playerId, typedSchema);
        data.put(schemaType, loaded);
        if (loaded != null) {
            isNew = false;
        }
        return loaded;
    }

    public <T> void set(Class<T> schemaType, T value) {
        if (value == null) return;
        data.put(schemaType, value);
        
        // Mark as dirty if dirty tracking is enabled
        if (dirtyTrackingEnabled && DirtyDataManager.isInitialized()) {
            var schema = PlayerDataRegistry.allSchemas().stream()
                    .filter(s -> s.type().equals(schemaType))
                    .findFirst().orElse(null);
            if (schema != null) {
                DirtyDataManager.markDirty(playerId, schema.schemaKey(), value, DirtyDataEntry.ChangeType.UPDATE);
            }
        }
    }

    public <T> void save(Class<T> schemaType, T value) {
        save(schemaType, value, false);
    }
    
    /**
     * Saves data with option to force immediate persistence.
     *
     * @param schemaType The schema type
     * @param value The value to save
     * @param immediate Whether to save immediately or use dirty tracking
     */
    public <T> void save(Class<T> schemaType, T value, boolean immediate) {
        if (value == null) return;
        var schema = PlayerDataRegistry.allSchemas().stream()
                .filter(s -> s.type().equals(schemaType))
                .findFirst().orElse(null);
        if (schema == null) throw new IllegalArgumentException("Schema not registered: " + schemaType);
        if (!schemaType.isInstance(value))
            throw new IllegalArgumentException("Value type does not match schema: " + value.getClass());
        @SuppressWarnings("unchecked")
        PlayerDataSchema<T> typedSchema = (PlayerDataSchema<T>) schema;
        
        // Use dirty tracking if enabled and not forced immediate
        if (dirtyTrackingEnabled && !immediate) {
            PlayerStorageManager.saveWithDirtyTracking(playerId, typedSchema, value, false);
        } else {
            PlayerStorageManager.save(playerId, typedSchema, value);
        }
        
        data.put(schemaType, value);
        isNew = false;
    }

    public <T> CompletableFuture<Void> saveAsync(Class<T> schemaType, T value) {
        return saveAsync(schemaType, value, false);
    }
    
    /**
     * Asynchronously saves data with option to force immediate persistence.
     *
     * @param schemaType The schema type
     * @param value The value to save
     * @param immediate Whether to save immediately or use dirty tracking
     */
    public <T> CompletableFuture<Void> saveAsync(Class<T> schemaType, T value, boolean immediate) {
        if (value == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> save(schemaType, value, immediate), ASYNC_EXECUTOR);
    }

    /**
     * Loads all known schemas synchronously — internal use only
     */
    public void loadAll() {
        for (PlayerDataSchema<?> schema : PlayerDataRegistry.allSchemas()) {
            Object loaded = PlayerStorageManager.loadOrCreate(playerId, schema);
            if (loaded != null) {
                data.put(schema.type(), loaded);
                isNew = false;
            }
        }
    }

    /**
     * Asynchronous safe version of loadAll
     */
    public CompletableFuture<Void> loadAllAsync() {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (PlayerDataSchema<?> schema : PlayerDataRegistry.allSchemas()) {
            futures.add(CompletableFuture.runAsync(() -> {
                Object loaded = PlayerStorageManager.loadOrCreate(playerId, schema);
                if (loaded != null) {
                    data.put(schema.type(), loaded);
                    isNew = false;
                }
            }, ASYNC_EXECUTOR));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Synchronous save of all loaded data
     */
    public void saveAll() {
        saveAll(false);
    }
    
    /**
     * Synchronous save of all loaded data with option to force immediate persistence.
     *
     * @param immediate Whether to save immediately or use dirty tracking
     */
    public void saveAll(boolean immediate) {
        for (var entry : data.entrySet()) {
            if (entry.getValue() == null) continue;
            var schema = PlayerDataRegistry.allSchemas().stream()
                    .filter(s -> s.type().equals(entry.getKey()))
                    .findFirst().orElse(null);
            if (schema != null) {
                @SuppressWarnings("unchecked")
                PlayerDataSchema<Object> typedSchema = (PlayerDataSchema<Object>) schema;
                saveSchema(typedSchema, entry.getValue(), immediate);
            }
        }
        isNew = false;
    }

    /**
     * Asynchronous save of all loaded data
     */
    public CompletableFuture<Void> saveAllAsync() {
        return saveAllAsync(false);
    }
    
    /**
     * Asynchronous save of all loaded data with option to force immediate persistence.
     *
     * @param immediate Whether to save immediately or use dirty tracking
     */
    public CompletableFuture<Void> saveAllAsync(boolean immediate) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (var entry : data.entrySet()) {
            if (entry.getValue() == null) continue;
            var schema = PlayerDataRegistry.allSchemas().stream()
                    .filter(s -> s.type().equals(entry.getKey()))
                    .findFirst().orElse(null);
            if (schema != null) {
                @SuppressWarnings("unchecked")
                PlayerDataSchema<Object> typedSchema = (PlayerDataSchema<Object>) schema;
                futures.add(CompletableFuture.runAsync(() -> saveSchema(typedSchema, entry.getValue(), immediate), ASYNC_EXECUTOR));
            }
        }
        isNew = false;
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private <T> void saveSchema(PlayerDataSchema<T> schema, Object value) {
        saveSchema(schema, value, false);
    }
    
    private <T> void saveSchema(PlayerDataSchema<T> schema, Object value, boolean immediate) {
        T castedValue = schema.type().cast(value);
        if (dirtyTrackingEnabled && !immediate) {
            PlayerStorageManager.saveWithDirtyTracking(playerId, schema, castedValue, false);
        } else {
            PlayerStorageManager.save(playerId, schema, castedValue);
        }
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public boolean isNew() {
        return isNew;
    }
    
    /**
     * Checks if this profile has any dirty data.
     *
     * @return true if the profile has dirty data, false otherwise
     */
    public boolean hasDirtyData() {
        return PlayerStorageManager.hasDirtyData(playerId);
    }
    
    /**
     * Gets the count of dirty data entries for this profile.
     *
     * @return The count of dirty data entries
     */
    public int getDirtyDataCount() {
        return PlayerStorageManager.getDirtyDataCount(playerId);
    }
    
    /**
     * Saves only the dirty data for this profile.
     *
     * @return The number of dirty entries that were persisted
     */
    public int saveDirtyData() {
        return PlayerStorageManager.saveDirtyData(playerId);
    }
    
    /**
     * Asynchronously saves only the dirty data for this profile.
     *
     * @return A CompletableFuture containing the number of dirty entries that were persisted
     */
    public CompletableFuture<Integer> saveDirtyDataAsync() {
        return PlayerStorageManager.saveDirtyDataAsync(playerId);
    }
    
    /**
     * Enables or disables dirty tracking for this profile.
     *
     * @param enabled Whether to enable dirty tracking
     */
    public void setDirtyTrackingEnabled(boolean enabled) {
        this.dirtyTrackingEnabled = enabled;
        LOGGER.log(Level.INFO, "Dirty tracking {0} for player {1}",
                  new Object[]{enabled ? "enabled" : "disabled", playerId});
    }
    
    /**
     * Checks if dirty tracking is enabled for this profile.
     *
     * @return true if dirty tracking is enabled, false otherwise
     */
    public boolean isDirtyTrackingEnabled() {
        return dirtyTrackingEnabled;
    }
    
    /**
     * Flushes all dirty data for this profile by persisting it immediately.
     * This method saves all dirty data and clears the dirty flags.
     *
     * @return A CompletableFuture that completes when all dirty data has been flushed
     */
    public CompletableFuture<Integer> flushDirtyData() {
        return saveDirtyDataAsync();
    }
}
