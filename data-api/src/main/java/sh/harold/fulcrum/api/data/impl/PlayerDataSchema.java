package sh.harold.fulcrum.api.data.impl;

public interface PlayerDataSchema<T> {
    String schemaKey();

    Class<T> type();
}
