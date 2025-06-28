package sh.harold.fulcrum.api;

public interface PlayerDataSchema<T> {
    String schemaKey();

    Class<T> type();
}
