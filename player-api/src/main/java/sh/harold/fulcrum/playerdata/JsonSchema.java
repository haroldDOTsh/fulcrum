package sh.harold.fulcrum.playerdata;

import java.util.UUID;

public abstract class JsonSchema<T> implements PlayerDataSchema<T> {
    @Override
    public abstract String schemaKey();

    @Override
    public abstract Class<T> type();

    @Override
    public abstract T load(UUID uuid);

    @Override
    public abstract void save(UUID uuid, T data);

    public abstract T deserialize(UUID uuid, String json);
    public abstract String serialize(UUID uuid, T data);
}
