package sh.harold.fulcrum.api;

public abstract class TableSchema<T> implements PlayerDataSchema<T> {
    @Override
    public abstract String schemaKey();

    @Override
    public abstract Class<T> type();

    // No load/save here; handled by backend
}
