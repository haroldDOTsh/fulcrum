package sh.harold.fulcrum.playerdata;

import java.util.*;
import java.util.function.Supplier;

public final class PlayerProfile {
    private final UUID playerId;
    private final Map<Class<?>, Object> data = new HashMap<>();

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> schemaType) {
        if (data.containsKey(schemaType)) return (T) data.get(schemaType);
        var schema = PlayerDataRegistry.get(schemaType);
        if (schema == null) throw new IllegalArgumentException("Schema not registered: " + schemaType);
        T loaded = PlayerStorageManager.load(playerId, schema);
        data.put(schemaType, loaded);
        return loaded;
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
        data.put(schemaType, value);
    }

    public void loadAll() {
        for (PlayerDataSchema<?> schema : PlayerDataRegistry.allSchemas()) {
            Object loaded = PlayerStorageManager.load(playerId, schema);
            data.put(schema.type(), loaded);
        }
    }

    public void saveAll() {
        for (var entry : data.entrySet()) {
            var schema = PlayerDataRegistry.get(entry.getKey());
            if (schema != null && entry.getValue() != null)
                saveSchema(schema, entry.getValue());
        }
    }

    private <T> void saveSchema(PlayerDataSchema<T> schema, Object value) {
        PlayerStorageManager.save(playerId, schema, schema.type().cast(value));
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
