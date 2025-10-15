package sh.harold.fulcrum.minigame.data;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Typed view over a minigame-specific player data collection.
 *
 * @param <T> document type managed by the collection
 */
public interface MinigameCollection<T> {

    /**
     * Load the document for the given player, returning a new instance if none exists.
     */
    T load(UUID playerId);

    /**
     * Apply a mutation to the player's document and persist the result.
     */
    void upsert(UUID playerId, Consumer<T> mutator);

    /**
     * Remove the player's document from the collection.
     */
    void delete(UUID playerId);
}
