package sh.harold.fulcrum.playerdata;

import sh.harold.fulcrum.registry.PlayerDataRegistry;

import java.util.UUID;

public final class PlayerStorageManager {
    private PlayerStorageManager() {}

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
}
