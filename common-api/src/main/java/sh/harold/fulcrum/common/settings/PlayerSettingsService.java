package sh.harold.fulcrum.common.settings;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Contract for retrieving and mutating player-facing settings across the network.
 * Implementations should remain non-blocking where possible, typically delegating to
 * the Redis-backed session cache and persisting asynchronously to the canonical store.
 */
public interface PlayerSettingsService {

    /**
     * Check whether the player has opted into network debug messaging.
     */
    default CompletionStage<Boolean> isDebugEnabled(UUID playerId) {
        return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    /**
     * Toggle the player debug flag.
     */
    default CompletionStage<Void> setDebugEnabled(UUID playerId, boolean enabled) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Fetch a tiered level for the provided key (privacy, visibility, etc.).
     */
    default CompletionStage<SettingLevel> getLevel(UUID playerId, String key, SettingLevel fallback) {
        return CompletableFuture.completedFuture(fallback);
    }

    /**
     * Persist a tiered level for the provided key.
     */
    default CompletionStage<Void> setLevel(UUID playerId, String key, SettingLevel level) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Obtain a scoped accessor for minigame-specific settings.
     */
    GameSettingsScope forGame(String gameId);

    /**
     * Represents settings tied to a specific minigame or experience.
     */
    interface GameSettingsScope {

        /**
         * Retrieve every stored setting for the player within this scope.
         */
        CompletionStage<Map<String, Object>> getAll(UUID playerId);

        /**
         * Retrieve a typed value for the supplied key, if present.
         */
        <T> CompletionStage<Optional<T>> get(UUID playerId, String key, Class<T> type);

        /**
         * Set or replace the value for the supplied key.
         */
        CompletionStage<Void> set(UUID playerId, String key, Object value);

        /**
         * Remove a value for the supplied key, if it exists.
         */
        CompletionStage<Void> remove(UUID playerId, String key);
    }
}
