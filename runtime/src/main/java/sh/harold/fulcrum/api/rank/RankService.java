package sh.harold.fulcrum.api.rank;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for managing player ranks.
 * Handles getting, setting, and resolving effective ranks.
 */
public interface RankService {

    /**
     * Gets the primary rank for a player.
     *
     * @param playerId the UUID of the player
     * @return the player's primary rank
     */
    CompletableFuture<Rank> getPrimaryRank(UUID playerId);

    /**
     * Gets the primary rank for a player synchronously.
     *
     * @param playerId the UUID of the player
     * @return the player's primary rank, or DEFAULT if not found
     */
    Rank getPrimaryRankSync(UUID playerId);

    /**
     * Gets all ranks for a player.
     *
     * @param playerId the UUID of the player
     * @return set of all ranks the player has
     */
    CompletableFuture<Set<Rank>> getAllRanks(UUID playerId);

    /**
     * Sets the primary rank for a player.
     *
     * @param playerId the UUID of the player
     * @param rank     the rank to set
     * @param context  information about who initiated the change
     * @return future that completes when the rank is set
     */
    CompletableFuture<Void> setPrimaryRank(UUID playerId, Rank rank, RankChangeContext context);

    /**
     * Sets the primary rank for a player with system context.
     *
     * @param playerId the UUID of the player
     * @param rank     the rank to set
     * @return future that completes when the rank is set
     */
    default CompletableFuture<Void> setPrimaryRank(UUID playerId, Rank rank) {
        return setPrimaryRank(playerId, rank, RankChangeContext.system());
    }

    /**
     * Adds a rank to a player without removing existing ranks.
     *
     * @param playerId the UUID of the player
     * @param rank     the rank to add
     * @param context  information about who initiated the change
     * @return future that completes when the rank is added
     */
    CompletableFuture<Void> addRank(UUID playerId, Rank rank, RankChangeContext context);

    /**
     * Adds a rank to a player using system context.
     *
     * @param playerId the UUID of the player
     * @param rank     the rank to add
     * @return future that completes when the rank is added
     */
    default CompletableFuture<Void> addRank(UUID playerId, Rank rank) {
        return addRank(playerId, rank, RankChangeContext.system());
    }

    /**
     * Removes a rank from a player.
     *
     * @param playerId the UUID of the player
     * @param rank     the rank to remove
     * @param context  information about who initiated the change
     * @return future that completes when the rank is removed
     */
    CompletableFuture<Void> removeRank(UUID playerId, Rank rank, RankChangeContext context);

    /**
     * Removes a rank from a player using system context.
     *
     * @param playerId the UUID of the player
     * @param rank     the rank to remove
     * @return future that completes when the rank is removed
     */
    default CompletableFuture<Void> removeRank(UUID playerId, Rank rank) {
        return removeRank(playerId, rank, RankChangeContext.system());
    }

    /**
     * Resolves the effective rank for a player when they have multiple ranks.
     * The effective rank is determined by priority (highest priority wins).
     *
     * @param playerId the UUID of the player
     * @return the effective rank based on priority
     */
    CompletableFuture<Rank> getEffectiveRank(UUID playerId);

    /**
     * Gets the effective rank for a player synchronously.
     *
     * @param playerId the UUID of the player
     * @return the effective rank based on priority
     */
    Rank getEffectiveRankSync(UUID playerId);

    /**
     * Convenience method to get the primary rank for a Player object.
     *
     * @param player the player
     * @return the player's primary rank
     */
    default CompletableFuture<Rank> getPrimaryRank(Player player) {
        return getPrimaryRank(player.getUniqueId());
    }

    /**
     * Convenience method to get the effective rank for a Player object.
     *
     * @param player the player
     * @return the player's effective rank
     */
    default CompletableFuture<Rank> getEffectiveRank(Player player) {
        return getEffectiveRank(player.getUniqueId());
    }

    /**
     * Checks if a player has a specific rank.
     *
     * @param playerId the UUID of the player
     * @param rank     the rank to check
     * @return true if the player has the rank
     */
    CompletableFuture<Boolean> hasRank(UUID playerId, Rank rank);

    /**
     * Checks if a player has any staff rank.
     *
     * @param playerId the UUID of the player
     * @return true if the player has any staff rank
     */
    CompletableFuture<Boolean> isStaff(UUID playerId);

    /**
     * Clears all ranks for a player and sets them to DEFAULT.
     *
     * @param playerId the UUID of the player
     * @param context  information about who initiated the change
     * @return future that completes when ranks are cleared
     */
    CompletableFuture<Void> resetRanks(UUID playerId, RankChangeContext context);

    /**
     * Clears all ranks for a player using system context.
     *
     * @param playerId the UUID of the player
     * @return future that completes when ranks are cleared
     */
    default CompletableFuture<Void> resetRanks(UUID playerId) {
        return resetRanks(playerId, RankChangeContext.system());
    }
}
