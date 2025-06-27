package sh.harold.fulcrum.registry;

import sh.harold.fulcrum.api.LifecycleAwareSchema;
import sh.harold.fulcrum.api.PlayerDataSchema;
import sh.harold.fulcrum.backend.PlayerDataBackend;

import java.util.*;

public final class PlayerDataRegistry {
    private static final Map<PlayerDataSchema<?>, PlayerDataBackend> schemaBackends = new HashMap<>();

    private PlayerDataRegistry() {
    }

    public static <T> void registerSchema(PlayerDataSchema<T> schema, PlayerDataBackend backend) {
        schemaBackends.put(schema, backend);
    }

    public static <T> PlayerDataBackend getBackend(PlayerDataSchema<T> schema) {
        return schemaBackends.get(schema);
    }

    public static Collection<PlayerDataSchema<?>> allSchemas() {
        return Collections.unmodifiableCollection(schemaBackends.keySet());
    }

    public static void clear() {
        schemaBackends.clear();
    }

    public static void notifyJoin(UUID playerId) {
        for (var schema : allSchemas()) {
            if (schema instanceof LifecycleAwareSchema lifecycle) {
                lifecycle.onJoin(playerId);
            }
        }
    }

    public static void notifyQuit(UUID playerId) {
        for (var schema : allSchemas()) {
            if (schema instanceof LifecycleAwareSchema lifecycle) {
                lifecycle.onQuit(playerId);
            }
        }
    }
}
