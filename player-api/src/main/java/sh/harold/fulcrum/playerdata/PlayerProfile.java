package sh.harold.fulcrum.playerdata;

import java.util.*;

public final class PlayerProfile {
    private final UUID playerId;
    private final Map<Class<?>, Object> schemaData = new HashMap<>();
    private final PlayerDataRegistry registry;

    public PlayerProfile(UUID playerId, PlayerDataRegistry registry) {
        this.playerId = playerId;
        this.registry = registry;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        return (T) schemaData.get(type);
    }

    public <T> void set(Class<T> type, T data) {
        schemaData.put(type, data);
    }

    public void loadAll() {
        for (PlayerDataSchema<?> schema : registry.getRegisteredSchemas()) {
            Object data = schema.load(playerId);
            schemaData.put(schema.type(), data);
        }
    }

    public void saveAll() {
        for (PlayerDataSchema<?> schema : registry.getRegisteredSchemas()) {
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
