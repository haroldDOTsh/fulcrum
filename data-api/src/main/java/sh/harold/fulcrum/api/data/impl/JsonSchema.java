package sh.harold.fulcrum.api.data.impl;

import java.util.UUID;

public abstract class JsonSchema<T> implements PlayerDataSchema<T> {
    @Override
    public abstract String schemaKey();

    @Override
    public abstract Class<T> type();

    public abstract T deserialize(UUID uuid, String json);

    public abstract String serialize(UUID uuid, T data);
    
    @Override
    public T deserialize(java.sql.ResultSet rs) {
        try {
            String uuidStr = rs.getString("uuid");
            String jsonData = rs.getString("data");
            UUID uuid = uuidStr != null ? UUID.fromString(uuidStr) : null;
            return deserialize(uuid, jsonData);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize " + schemaKey() + " from ResultSet", e);
        }
    }
    
    // No load/save here; handled by backend
}
