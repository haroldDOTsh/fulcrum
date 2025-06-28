package sh.harold.fulcrum.api.data.backend.json;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryJsonStorageEngine {
    private final Map<UUID, Map<String, String>> storage = new ConcurrentHashMap<>();

    public void save(UUID uuid, String schemaKey, String json) {
        storage.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(schemaKey, json);
    }

    public String load(UUID uuid, String schemaKey) {
        var userMap = storage.get(uuid);
        if (userMap == null) return null;
        return userMap.get(schemaKey);
    }
}
