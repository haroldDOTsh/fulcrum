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
     * Retrieve the configured debug tier for the player.
     */
    default CompletionStage<PlayerDebugLevel> getDebugLevel(UUID playerId) {
        return CompletableFuture.completedFuture(PlayerDebugLevel.NONE);
    }

    /**
     * Persist the configured debug tier for the player.
     */
    default CompletionStage<Void> setDebugLevel(UUID playerId, PlayerDebugLevel level) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Check whether the player has opted into any network debug messaging.
     */
    default CompletionStage<Boolean> isDebugEnabled(UUID playerId) {
        return getDebugLevel(playerId).thenApply(PlayerDebugLevel::isEnabled);
    }

    /**
     * Toggle the player debug flag.
     */
    default CompletionStage<Void> setDebugEnabled(UUID playerId, boolean enabled) {
        return setDebugLevel(playerId, enabled ? PlayerDebugLevel.PLAYER : PlayerDebugLevel.NONE);
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
     * Retrieve a typed value for the provided key from the global settings namespace.
     */
    default <T> CompletionStage<Optional<T>> getSetting(UUID playerId, String key, Class<T> type) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Set or replace the value for the provided key in the global settings namespace.
     */
    default CompletionStage<Void> setSetting(UUID playerId, String key, Object value) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Remove a value for the provided key in the global settings namespace, if present.
     */
    default CompletionStage<Void> removeSetting(UUID playerId, String key) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Convenience accessor for minigame-specific settings.
     */
    default GameSettingsScope forGame(String gameId) {
        return forScope(gameId, null);
    }

    /**
     * Obtain a scoped accessor for a family without a variant.
     */
    default GameSettingsScope forFamily(String family) {
        return forScope(family, null);
    }

    /**
     * Obtain a scoped accessor for settings persisted under a specific family/variant pair.
     */
    GameSettingsScope forScope(String family, String variant);

    /**
     * Represents settings tied to a specific experience family/variant.
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
