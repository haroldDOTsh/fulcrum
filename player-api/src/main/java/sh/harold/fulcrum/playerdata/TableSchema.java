package sh.harold.fulcrum.playerdata;

import java.util.UUID;

public abstract class TableSchema<T> implements PlayerDataSchema<T> {
    @Override
    public abstract String schemaKey();

    @Override
    public abstract Class<T> type();

    @Override
    public abstract T load(UUID uuid);

    @Override
    public abstract void save(UUID uuid, T data);

    // Add method stubs for relational schema logic as needed
}
