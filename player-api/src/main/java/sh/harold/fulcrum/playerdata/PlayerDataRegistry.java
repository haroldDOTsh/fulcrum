package sh.harold.fulcrum.playerdata;

import java.util.*;

public final class PlayerDataRegistry {
    private static final Map<Class<?>, PlayerDataSchema<?>> schemas = new HashMap<>();

    private PlayerDataRegistry() {}

    public static <T> void register(PlayerDataSchema<T> schema) {
        schemas.put(schema.type(), schema);
    }

    @SuppressWarnings("unchecked")
    public static <T> PlayerDataSchema<T> get(Class<T> type) {
        return (PlayerDataSchema<T>) schemas.get(type);
    }

    public static Collection<PlayerDataSchema<?>> allSchemas() {
        return Collections.unmodifiableCollection(schemas.values());
    }

    public static void clear() {
        schemas.clear();
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
