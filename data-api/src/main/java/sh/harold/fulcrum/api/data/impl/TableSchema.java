package sh.harold.fulcrum.api.data.impl;

public abstract class TableSchema<T> implements PlayerDataSchema<T> {
    @Override
    public abstract String schemaKey();

    @Override
    public abstract Class<T> type();

    @Override
    public T deserialize(java.sql.ResultSet rs) {
        throw new UnsupportedOperationException("TableSchema subclasses should implement deserialize(ResultSet) method or use AutoTableSchema");
    }

    // No load/save here; handled by backend
}
