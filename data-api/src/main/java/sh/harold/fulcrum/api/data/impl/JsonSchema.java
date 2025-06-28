package sh.harold.fulcrum.api.data.impl;

import java.util.UUID;

public abstract class JsonSchema<T> implements PlayerDataSchema<T> {
    @Override
    public abstract String schemaKey();

    @Override
    public abstract Class<T> type();

    public abstract T deserialize(UUID uuid, String json);

    public abstract String serialize(UUID uuid, T data);
    // No load/save here; handled by backend
}
