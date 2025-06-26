package sh.harold.fulcrum.playerdata;

import java.util.UUID;

public final class PlayerStorageManager {
    private PlayerStorageManager() {}

    public static <T> T load(UUID playerId, PlayerDataSchema<T> schema) {
        if (schema instanceof JsonSchema<?>) {
            return schema.load(playerId);
        } else if (schema instanceof TableSchema<?>) {
            return schema.load(playerId);
        }
        throw new UnsupportedOperationException("Unknown schema engine: " + schema.getClass());
    }

    public static <T> void save(UUID playerId, PlayerDataSchema<T> schema, T data) {
        if (schema instanceof JsonSchema<?>) {
            schema.save(playerId, data);
        } else if (schema instanceof TableSchema<?>) {
            schema.save(playerId, data);
        } else {
            throw new UnsupportedOperationException("Unknown schema engine: " + schema.getClass());
        }
    }
}
