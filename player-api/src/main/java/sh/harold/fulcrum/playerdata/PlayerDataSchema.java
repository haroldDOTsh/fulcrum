package sh.harold.fulcrum.playerdata;

import java.util.UUID;

public interface PlayerDataSchema<T> {
    String schemaKey();
    Class<T> type();
    T load(UUID uuid);
    void save(UUID uuid, T data);
}
