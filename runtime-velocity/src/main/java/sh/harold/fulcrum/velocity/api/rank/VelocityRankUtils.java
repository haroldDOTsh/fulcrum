package sh.harold.fulcrum.velocity.api.rank;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Utility class for rank-based permission checks on Velocity proxy.
 * Provides convenient methods to check player ranks and permissions.
 */
public final class VelocityRankUtils {
    
    private VelocityRankUtils() {
        // Utility class
    }
    
    /**
     * Asynchronously checks if a player has the specified rank or higher.
     * 
     * @param player the player to check
     * @param requiredRank the minimum required rank
     * @param rankReader the authority rank reader
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the player has the required rank or higher
     */
    public static CompletableFuture<Boolean> hasRankOrHigher(Player player, Rank requiredRank, 
                                                            DataAuthority.PlayerRankReader rankReader, Logger logger) {
        logger.debug("Checking rank for player {} ({}), requires {}",
            player.getUsername(), player.getUniqueId(), requiredRank.name());

        return getEffectiveRank(player, rankReader, logger).thenApply(playerRank -> {
            boolean hasPermission = playerRank.getPriority() >= requiredRank.getPriority();

            logger.debug("Player {} has rank {} (priority {}), needs {} (priority {}) -> {}",
                player.getUsername(), playerRank.name(), playerRank.getPriority(),
                requiredRank.name(), requiredRank.getPriority(),
                hasPermission ? "ALLOWED" : "DENIED");

            return hasPermission;
        }).exceptionally(e -> {
            logger.warn(
                "Rank check for player {} ({}) failed closed: {}",
                player.getUsername(),
                player.getUniqueId(),
                e.getMessage()
            );
            return false;
        });
    }
    
    /**
     * Checks if a CommandSource has the specified rank or higher.
     * Console is always treated as having admin privileges.
     * 
     * @param source the command source to check
     * @param requiredRank the minimum required rank
     * @param rankReader the authority rank reader
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the source has the required rank or higher
     */
    public static CompletableFuture<Boolean> hasRankOrHigher(CommandSource source, Rank requiredRank,
                                                            DataAuthority.PlayerRankReader rankReader, Logger logger) {
        // Console always has admin privileges
        if (source instanceof ConsoleCommandSource) {
            logger.debug("Permission check for console - always allowed");
            return CompletableFuture.completedFuture(true);
        }
        
        if (source instanceof Player) {
            return hasRankOrHigher((Player) source, requiredRank, rankReader, logger);
        }
        
        // Unknown source type - deny by default
        logger.debug("Unknown command source type: {}", source.getClass().getName());
        return CompletableFuture.completedFuture(false);
    }
    
    /**
     * Checks if a player is admin (ADMIN rank).
     * 
     * @param player the player to check
     * @param rankReader the authority rank reader
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the player is admin
     */
    public static CompletableFuture<Boolean> isAdmin(Player player, DataAuthority.PlayerRankReader rankReader,
                                                     Logger logger) {
        return hasRankOrHigher(player, Rank.ADMIN, rankReader, logger);
    }
    
    /**
     * Checks if a CommandSource is admin.
     * Console is always treated as admin.
     * 
     * @param source the command source to check
     * @param rankReader the authority rank reader
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the source is admin
     */
    public static CompletableFuture<Boolean> isAdmin(CommandSource source, DataAuthority.PlayerRankReader rankReader,
                                                     Logger logger) {
        if (source instanceof ConsoleCommandSource) {
            return CompletableFuture.completedFuture(true);
        }
        
        if (source instanceof Player) {
            return isAdmin((Player) source, rankReader, logger);
        }
        
        return CompletableFuture.completedFuture(false);
    }
    
    /**
     * Gets the effective rank of a Player asynchronously.
     * 
     * @param player the player
     * @param rankReader the authority rank reader
     * @param logger the logger for debugging
     * @return CompletableFuture with the player's rank
     */
    public static CompletableFuture<Rank> getEffectiveRank(Player player, DataAuthority.PlayerRankReader rankReader,
                                                           Logger logger) {
        return rankReader.quoteRanks(player.getUniqueId(), DataAuthority.ReadRequirement.eventual())
            .thenApply(read -> {
                if (!read.satisfied()) {
                    if (read.quote().status() == DataAuthority.ReadQuoteStatus.NOT_FOUND) {
                        logger.debug("Rank projection not found for {}, returning DEFAULT", player.getUsername());
                        return Rank.DEFAULT;
                    }
                    throw new IllegalStateException("Rank projection for " + player.getUsername()
                        + " is not safe to use: " + read.quote().status() + " " + read.quote().message());
                }

                DataAuthority.PlayerRankSnapshot snapshot = read.snapshot().orElseThrow();
                String rankStr = snapshot.primaryRank();
                if (rankStr == null || rankStr.isBlank()) {
                    logger.debug("Rank projection not found for {}, returning DEFAULT", player.getUsername());
                    return Rank.DEFAULT;
                }

                try {
                    return Rank.valueOf(rankStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid rank '{}' for player {}, returning DEFAULT", 
                               rankStr, player.getUsername());
                    return Rank.DEFAULT;
                }
            })
            .exceptionally(e -> {
                logger.error("Error getting rank for {}: {}", player.getUsername(), e.getMessage());
                throw new java.util.concurrent.CompletionException(e);
            })
            .toCompletableFuture();
    }
    
    /**
     * Gets the effective rank of a CommandSource.
     * Console is treated as ADMIN.
     * 
     * @param source the command source
     * @param rankReader the authority rank reader
     * @param logger the logger for debugging
     * @return CompletableFuture with the source's rank
     */
    public static CompletableFuture<Rank> getEffectiveRank(CommandSource source,
                                                           DataAuthority.PlayerRankReader rankReader,
                                                           Logger logger) {
        if (source instanceof ConsoleCommandSource) {
            return CompletableFuture.completedFuture(Rank.ADMIN);
        }
        
        if (source instanceof Player) {
            return getEffectiveRank((Player) source, rankReader, logger);
        }
        
        return CompletableFuture.completedFuture(Rank.DEFAULT);
    }
}
