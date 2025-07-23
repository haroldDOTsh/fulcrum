package sh.harold.fulcrum.playerdata;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.UUID;

/**
 * Mock PlayerDataBackend for testing purposes.
 * This provides a minimal implementation that can be used in tests
 * without requiring actual database connections or storage systems.
 */
public class MockPlayerDataBackend implements PlayerDataBackend {
    
    @Override
    public <T> T load(UUID playerId, PlayerDataSchema<T> schema) {
        // For testing purposes, return null or create a default instance
        try {
            return schema.type().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }
    
    @Override
    public <T> void save(UUID playerId, PlayerDataSchema<T> schema, T data) {
        // For testing purposes, do nothing
    }
    
    @Override
    public <T> T loadOrCreate(UUID playerId, PlayerDataSchema<T> schema) {
        // For testing purposes, try to create a new instance
        try {
            return schema.type().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }
}