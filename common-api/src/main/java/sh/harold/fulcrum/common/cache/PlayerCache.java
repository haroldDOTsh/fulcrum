package sh.harold.fulcrum.common.cache;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

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
     * Obtain a cache-backed view of a scoped player document (for example a {@code player_data_xxx} collection).
     *
     * @param family  logical family identifier
     * @param variant optional variant identifier (may be {@code null})
     */
    CachedDocument scoped(String family, String variant, UUID playerId);

    /**
     * Executor used to run asynchronous cache operations.
     */
    default Executor asyncExecutor() {
        return ForkJoinPool.commonPool();
    }

    interface CachedDocument {

        /**
         * Executor backing asynchronous document operations.
         */
        Executor asyncExecutor();

        /**
         * Retrieve the value stored at {@code key}, if present.
         */
        <T> Optional<T> get(String key, Class<T> type);

        /**
         * Asynchronously retrieve the value stored at {@code key}, if present.
         */
        default <T> CompletionStage<Optional<T>> getAsync(String key, Class<T> type) {
            return CompletableFuture.supplyAsync(() -> get(key, type), asyncExecutor());
        }

        /**
         * Assign the value at {@code key}, creating intermediate maps as needed.
         * Supplying {@code null} clears the value.
         */
        void set(String key, Object value);

        /**
         * Asynchronously assign the value at {@code key}, creating intermediate maps as needed.
         * Supplying {@code null} clears the value.
         */
        default CompletionStage<Void> setAsync(String key, Object value) {
            return CompletableFuture.runAsync(() -> set(key, value), asyncExecutor());
        }

        /**
         * Remove the value stored at {@code key}, if present.
         */
        void remove(String key);

        /**
         * Asynchronously remove the value stored at {@code key}, if present.
         */
        default CompletionStage<Void> removeAsync(String key) {
            return CompletableFuture.runAsync(() -> remove(key), asyncExecutor());
        }

        /**
         * Return a snapshot of the underlying map for inspection or bulk operations.
         */
        Map<String, Object> snapshot();

        /**
         * Asynchronously return a snapshot of the underlying map for inspection or bulk operations.
         */
        default CompletionStage<Map<String, Object>> snapshotAsync() {
            return CompletableFuture.supplyAsync(this::snapshot, asyncExecutor());
        }
    }
}
