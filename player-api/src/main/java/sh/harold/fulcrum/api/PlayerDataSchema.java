package sh.harold.fulcrum.playerdata;

public interface PlayerDataSchema<T> {
    String schemaKey();
    Class<T> type();
}
