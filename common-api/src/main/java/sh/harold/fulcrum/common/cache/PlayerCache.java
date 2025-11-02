package sh.harold.fulcrum.common.cache;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade for accessing player-scoped data through the session cache.
 * Implementations should keep the hot sections of the player document in Redis
 * and fall back to the persistent store when no session is active.
 */
public interface PlayerCache {

    /**
     * Obtain a cache-backed view of the global player document (`players` collection).
     */
    CachedDocument root(UUID playerId);

    /**
     * Obtain a cache-backed view of a scoped player document (`player_data_<family>` collection).
     *
     * @param family  logical family identifier
     * @param variant optional variant identifier (may be {@code null})
     */
    CachedDocument scoped(String family, String variant, UUID playerId);

    interface CachedDocument {

        /**
         * Retrieve the value stored at {@code key}, if present.
         */
        <T> Optional<T> get(String key, Class<T> type);

        /**
         * Assign the value at {@code key}, creating intermediate maps as needed.
         * Supplying {@code null} clears the value.
         */
        void set(String key, Object value);

        /**
         * Remove the value stored at {@code key}, if present.
         */
        void remove(String key);

        /**
         * Return a snapshot of the underlying map for inspection or bulk operations.
         */
        Map<String, Object> snapshot();
    }
}
