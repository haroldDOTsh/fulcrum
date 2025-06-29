package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.LifecycleAwareSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.*;

public final class PlayerDataRegistry {
    private static final Map<PlayerDataSchema<?>, PlayerDataBackend> schemaBackends = new HashMap<>();

    private PlayerDataRegistry() {
    }

    public static <T> void registerSchema(PlayerDataSchema<T> schema, PlayerDataBackend backend) {
        schemaBackends.put(schema, backend);
        // Automatic SQL table creation and schema version enforcement
        if (schema instanceof sh.harold.fulcrum.api.data.backend.core.AutoTableSchema<?> autoSchema &&
            backend instanceof sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend sqlBackend) {
            try {
                autoSchema.ensureTableAndVersion(sqlBackend.getConnection());
            } catch (Exception e) {
                throw new RuntimeException("Failed to ensure table and schema version for " + schema.schemaKey(), e);
            }
        }
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
