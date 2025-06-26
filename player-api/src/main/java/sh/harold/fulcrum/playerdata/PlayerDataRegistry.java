package sh.harold.fulcrum.playerdata;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataRegistry {
    private final Map<Class<?>, PlayerDataSchema<?>> schemas = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Class<?>, Object>> data = new ConcurrentHashMap<>();

    public <T> void register(PlayerDataSchema<T> schema) {
        schemas.put(schema.type(), schema);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(UUID uuid, Class<T> type) {
        var userMap = data.get(uuid);
        if (userMap == null) return null;
        return (T) userMap.get(type);
    }

    public <T> void set(UUID uuid, Class<T> type, T value) {
        data.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(type, value);
    }
}
