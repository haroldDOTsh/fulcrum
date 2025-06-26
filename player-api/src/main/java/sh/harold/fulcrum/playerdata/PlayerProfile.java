package sh.harold.fulcrum.playerdata;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class PlayerProfile {
    private final UUID playerId;
    private final Map<Class<?>, Object> data = new ConcurrentHashMap<>();
    private static final Executor ASYNC_EXECUTOR = Executors.newCachedThreadPool();

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }

    /** Internal, synchronous. Requires prior load or safe context. */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> schemaType) {
        if (data.containsKey(schemaType)) return (T) data.get(schemaType);
        var schema = PlayerDataRegistry.get(schemaType);
        if (schema == null) throw new IllegalArgumentException("Schema not registered: " + schemaType);
        T loaded = PlayerStorageManager.load(playerId, schema);
        if (loaded != null) {
            data.put(schemaType, loaded);
        }
        return loaded;
    }

    /** Safe async version of get(...) */
    public <T> CompletableFuture<T> getAsync(Class<T> schemaType) {
        if (data.containsKey(schemaType)) {
            @SuppressWarnings("unchecked")
            T cached = (T) data.get(schemaType);
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            var schema = PlayerDataRegistry.get(schemaType);
            if (schema == null) throw new IllegalArgumentException("Schema not registered: " + schemaType);
            T loaded = PlayerStorageManager.load(playerId, schema);
            if (loaded != null) {
                data.put(schemaType, loaded);
            }
            return loaded;
        }, ASYNC_EXECUTOR);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> schemaType, Supplier<T> fallback) {
        if (data.containsKey(schemaType)) return (T) data.get(schemaType);
        var schema = PlayerDataRegistry.get(schemaType);
        if (schema == null) return fallback.get();
        T loaded = PlayerStorageManager.load(playerId, schema);
        data.put(schemaType, loaded);
        return loaded;
    }

    public <T> void set(Class<T> schemaType, T value) {
        if (value == null) return;
        data.put(schemaType, value);
    }

    public <T> void save(Class<T> schemaType, T value) {
        if (value == null) return;
        var schema = PlayerDataRegistry.get(schemaType);
        if (schema == null) throw new IllegalArgumentException("Schema not registered: " + schemaType);
        if (!schemaType.isInstance(value))
            throw new IllegalArgumentException("Value type does not match schema: " + value.getClass());
        PlayerStorageManager.save(playerId, schema, value);
        data.put(schemaType, value);
    }

    public <T> CompletableFuture<Void> saveAsync(Class<T> schemaType, T value) {
        if (value == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> save(schemaType, value), ASYNC_EXECUTOR);
    }

    /** Loads all known schemas synchronously — internal use only */
    public void loadAll() {
        for (PlayerDataSchema<?> schema : PlayerDataRegistry.allSchemas()) {
            Object loaded = PlayerStorageManager.load(playerId, schema);
            if (loaded != null) {
                data.put(schema.type(), loaded);
            }
        }
    }

    /** Asynchronous safe version of loadAll */
    public CompletableFuture<Void> loadAllAsync() {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (PlayerDataSchema<?> schema : PlayerDataRegistry.allSchemas()) {
            futures.add(CompletableFuture.runAsync(() -> {
                Object loaded = PlayerStorageManager.load(playerId, schema);
                if (loaded != null) {
                    data.put(schema.type(), loaded);
                }
            }, ASYNC_EXECUTOR));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** Synchronous save of all loaded data */
    public void saveAll() {
        for (var entry : data.entrySet()) {
            if (entry.getValue() == null) continue;
            var schema = PlayerDataRegistry.get(entry.getKey());
            if (schema != null)
                saveSchema(schema, entry.getValue());
        }
    }

    /** Asynchronous save of all loaded data */
    public CompletableFuture<Void> saveAllAsync() {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (var entry : data.entrySet()) {
            if (entry.getValue() == null) continue;
            var schema = PlayerDataRegistry.get(entry.getKey());
            if (schema != null) {
                futures.add(CompletableFuture.runAsync(() -> saveSchema(schema, entry.getValue()), ASYNC_EXECUTOR));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private <T> void saveSchema(PlayerDataSchema<T> schema, Object value) {
        PlayerStorageManager.save(playerId, schema, schema.type().cast(value));
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
