package sh.harold.fulcrum.api.data.backend;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Unified backend interface for player data storage (SQL, JSON, etc).
 */
public interface PlayerDataBackend {
    <T> T load(UUID uuid, PlayerDataSchema<T> schema);

    <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data);

    <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema);
    
    /**
     * Saves multiple data entries in a batch operation for better performance.
     * This method is optimized for bulk operations and dirty data persistence.
     *
     * @param entries A map of UUID to schema-data pairs to save
     * @return The number of entries successfully saved
     */
    default int saveBatch(Map<UUID, Map<PlayerDataSchema<?>, Object>> entries) {
        int savedCount = 0;
        for (Map.Entry<UUID, Map<PlayerDataSchema<?>, Object>> playerEntry : entries.entrySet()) {
            UUID playerId = playerEntry.getKey();
            for (Map.Entry<PlayerDataSchema<?>, Object> schemaEntry : playerEntry.getValue().entrySet()) {
                try {
                    @SuppressWarnings("unchecked")
                    PlayerDataSchema<Object> schema = (PlayerDataSchema<Object>) schemaEntry.getKey();
                    save(playerId, schema, schemaEntry.getValue());
                    savedCount++;
                } catch (Exception e) {
                    // Log the error but continue with other entries
                    System.err.println("Failed to save data for player " + playerId + ", schema " + schemaEntry.getKey().schemaKey() + ": " + e.getMessage());
                }
            }
        }
        return savedCount;
    }
    
    /**
     * Saves only specific changed fields for a player data entry.
     * This method is used for optimized dirty data persistence when only certain fields have changed.
     *
     * @param uuid The player UUID
     * @param schema The data schema
     * @param data The complete data object
     * @param changedFields The specific fields that have changed (optional, can be null for full save)
     * @return true if save was successful, false otherwise
     */
    default <T> boolean saveChangedFields(UUID uuid, PlayerDataSchema<T> schema, T data, Collection<String> changedFields) {
        try {
            save(uuid, schema, data);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to save changed fields for player " + uuid + ", schema " + schema.schemaKey() + ": " + e.getMessage());
            return false;
        }
    }
}
