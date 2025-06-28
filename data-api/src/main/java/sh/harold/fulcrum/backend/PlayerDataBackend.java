package sh.harold.fulcrum.backend;

import sh.harold.fulcrum.api.PlayerDataSchema;

import java.util.UUID;

/**
 * Unified backend interface for player data storage (SQL, JSON, etc).
 */
public interface PlayerDataBackend {
    <T> T load(UUID uuid, PlayerDataSchema<T> schema);

    <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data);
}
