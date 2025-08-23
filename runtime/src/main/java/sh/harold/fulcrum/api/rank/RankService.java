package sh.harold.fulcrum.api.rank;

import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;
import sh.harold.fulcrum.api.rank.model.EffectiveRank;
import sh.harold.fulcrum.api.rank.model.MonthlyRankHistoryData;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main service interface for rank management.
 * Provides async operations for retrieving and modifying player ranks.
 */
public interface RankService {


    /**
     * Get the effective rank for a player (calculated from all rank types).
     */
    CompletableFuture<EffectiveRank> getEffectiveRank(UUID playerId);

    /**
     * Get the functional rank for a player (staff roles).
     */
    CompletableFuture<FunctionalRank> getFunctionalRank(UUID playerId);

    /**
     * Get the package rank for a player (permanent purchased ranks).
     */
    CompletableFuture<PackageRank> getPackageRank(UUID playerId);

    /**
     * Get the monthly rank for a player (time-limited subscription ranks).
     */
    CompletableFuture<MonthlyPackageRank> getMonthlyRank(UUID playerId);

    // ===== RANK MODIFICATION =====

    /**
     * Set or remove a functional rank for a player.
     * @param playerId The player's UUID
     * @param rank The functional rank to set, or null to remove
     */
    CompletableFuture<Void> setFunctionalRank(UUID playerId, FunctionalRank rank);

    /**
     * Set a package rank for a player.
     * @param playerId The player's UUID  
     * @param rank The package rank to set (cannot be null)
     */
    CompletableFuture<Void> setPackageRank(UUID playerId, PackageRank rank);

    /**
     * Grant a monthly rank to a player with expiration.
     * @param playerId The player's UUID
     * @param rank The monthly rank to grant
     * @param duration How long the rank should last
     */
    CompletableFuture<Void> grantMonthlyRank(UUID playerId, MonthlyPackageRank rank, Duration duration);

    /**
     * Remove a monthly rank from a player (set expiration to now).
     */
    CompletableFuture<Void> removeMonthlyRank(UUID playerId);


    /**
     * Check if a player has a specific permission based on their effective rank.
     */
    CompletableFuture<Boolean> hasPermission(UUID playerId, String permission);

    /**
     * Check if a player has the specified package rank or higher.
     */
    CompletableFuture<Boolean> hasRankOrHigher(UUID playerId, PackageRank minimumRank);

    /**
     * Check if a player has the specified functional rank or higher.
     */
    CompletableFuture<Boolean> hasFunctionalRankOrHigher(UUID playerId, FunctionalRank minimumRank);

    // ===== UTILITY METHODS =====

    /**
     * Expire all monthly ranks that have passed their expiration time.
     * This should be called periodically as a cleanup task.
     */
    CompletableFuture<Void> expireMonthlyRanks();

    /**
     * Get all players who have the specified package rank.
     */
    CompletableFuture<List<UUID>> getPlayersWithRank(PackageRank rank);

    /**
     * Get all players who have the specified functional rank.
     */
    CompletableFuture<List<UUID>> getPlayersWithFunctionalRank(FunctionalRank rank);

    /**
     * Get all players who have active monthly ranks.
     */
    CompletableFuture<List<UUID>> getPlayersWithActiveMonthlyRank();

    // ===== HISTORICAL MONTHLY RANK METHODS =====

    /**
     * Get the complete monthly rank history for a player.
     * Returns all monthly ranks this player has ever had, ordered by grant time (newest first).
     */
    CompletableFuture<List<MonthlyRankHistoryData>> getMonthlyRankHistory(UUID playerId);

    /**
     * Get the currently active monthly rank data for a player (including expiration details).
     * Returns null if the player has no active monthly rank.
     */
    CompletableFuture<MonthlyRankHistoryData> getActiveMonthlyRankData(UUID playerId);

    /**
     * Get all expired monthly ranks for a player.
     * Useful for showing what ranks a player previously had.
     */
    CompletableFuture<List<MonthlyRankHistoryData>> getExpiredMonthlyRanks(UUID playerId);
}