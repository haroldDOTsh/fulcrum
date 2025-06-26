package sh.harold.fulcrum.playerdata;

import java.util.*;

public final class PlayerProfile {
    private final UUID playerId;
    private final Map<Class<?>, Object> schemaData = new HashMap<>();

    public PlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        return (T) schemaData.get(type);
    }

    public <T> void set(Class<T> type, T data) {
        schemaData.put(type, data);
    }

    public void loadAll() {
        for (PlayerDataSchema<?> schema : PlayerDataRegistry.allSchemas()) {
            Object data = schema.load(playerId);
            schemaData.put(schema.type(), data);
        }
    }

    public void saveAll() {
        List<PlayerDataSchema<?>> schemas = new ArrayList<>(PlayerDataRegistry.allSchemas());
        Collections.reverse(schemas);
        for (PlayerDataSchema<?> schema : schemas) {
            Object data = schemaData.get(schema.type());
            if (data != null) saveSchema(schema, data);
        }
    }

    private <T> void saveSchema(PlayerDataSchema<T> schema, Object data) {
        schema.save(playerId, schema.type().cast(data));
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
