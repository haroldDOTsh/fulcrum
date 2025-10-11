package sh.harold.fulcrum.velocity.api.rank;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
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
     * @param dataAPI the DataAPI instance to query player data
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the player has the required rank or higher
     */
    public static CompletableFuture<Boolean> hasRankOrHigher(Player player, Rank requiredRank, 
                                                            DataAPI dataAPI, Logger logger) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Checking rank for player {} ({}), requires {}", 
                           player.getUsername(), player.getUniqueId(), requiredRank.name());
                
                // Get player document from DataAPI
                Document playerDoc = dataAPI.collection("players")
                    .document(player.getUniqueId().toString());
                
                if (!playerDoc.exists()) {
                    logger.debug("Player document not found for {}, denying access", player.getUsername());
                    return false;
                }
                
                // Get the player's rank from the document (supports new rankInfo schema)
                String rankStr = resolvePrimaryRank(playerDoc);
                Rank playerRank = parseRank(rankStr, player.getUsername(), logger);
                
                boolean hasPermission = playerRank.getPriority() >= requiredRank.getPriority();
                
                logger.debug("Player {} has rank {} (priority {}), needs {} (priority {}) -> {}",
                           player.getUsername(), playerRank.name(), playerRank.getPriority(),
                           requiredRank.name(), requiredRank.getPriority(),
                           hasPermission ? "ALLOWED" : "DENIED");
                
                return hasPermission;
                
            } catch (Exception e) {
                logger.error("Error checking rank for {}: {}", player.getUsername(), e.getMessage());
                e.printStackTrace();
                return false;
            }
        });
    }
    
    /**
     * Synchronously checks if a player has the specified rank or higher.
     * This is a blocking call and should be used carefully.
     * 
     * @param player the player to check
     * @param requiredRank the minimum required rank
     * @param dataAPI the DataAPI instance
     * @param logger the logger for debugging
     * @return true if the player has the required rank or higher
     */
    public static boolean hasRankOrHigherSync(Player player, Rank requiredRank,
                                             DataAPI dataAPI, Logger logger) {
        try {
            return hasRankOrHigher(player, requiredRank, dataAPI, logger).get();
        } catch (Exception e) {
            logger.error("Error in synchronous rank check for {}: {}", 
                        player.getUsername(), e.getMessage());
            return false;
        }
    }
    
    /**
     * Checks if a CommandSource has the specified rank or higher.
     * Console is always treated as having admin privileges.
     * 
     * @param source the command source to check
     * @param requiredRank the minimum required rank
     * @param dataAPI the DataAPI instance
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the source has the required rank or higher
     */
    public static CompletableFuture<Boolean> hasRankOrHigher(CommandSource source, Rank requiredRank,
                                                            DataAPI dataAPI, Logger logger) {
        // Console always has admin privileges
        if (source instanceof ConsoleCommandSource) {
            logger.debug("Permission check for console - always allowed");
            return CompletableFuture.completedFuture(true);
        }
        
        if (source instanceof Player) {
            return hasRankOrHigher((Player) source, requiredRank, dataAPI, logger);
        }
        
        // Unknown source type - deny by default
        logger.debug("Unknown command source type: {}", source.getClass().getName());
        return CompletableFuture.completedFuture(false);
    }
    
    /**
     * Checks if a player is admin (STAFF rank).
     * 
     * @param player the player to check
     * @param dataAPI the DataAPI instance
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the player is admin
     */
    public static CompletableFuture<Boolean> isAdmin(Player player, DataAPI dataAPI, Logger logger) {
        return hasRankOrHigher(player, Rank.STAFF, dataAPI, logger);
    }
    
    /**
     * Checks if a CommandSource is admin (STAFF).
     * Console is always treated as admin.
     * 
     * @param source the command source to check
     * @param dataAPI the DataAPI instance
     * @param logger the logger for debugging
     * @return CompletableFuture with true if the source is admin
     */
    public static CompletableFuture<Boolean> isAdmin(CommandSource source, DataAPI dataAPI, Logger logger) {
        if (source instanceof ConsoleCommandSource) {
            return CompletableFuture.completedFuture(true);
        }
        
        if (source instanceof Player) {
            return isAdmin((Player) source, dataAPI, logger);
        }
        
        return CompletableFuture.completedFuture(false);
    }
    
    /**
     * Gets the effective rank of a Player asynchronously.
     * 
     * @param player the player
     * @param dataAPI the DataAPI instance
     * @param logger the logger for debugging
     * @return CompletableFuture with the player's rank
     */
    public static CompletableFuture<Rank> getEffectiveRank(Player player, DataAPI dataAPI, Logger logger) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Document playerDoc = dataAPI.collection("players")
                    .document(player.getUniqueId().toString());
                
                if (!playerDoc.exists()) {
                    logger.debug("Player document not found for {}, returning DEFAULT", player.getUsername());
                    return Rank.DEFAULT;
                }
                
                String rankStr = resolvePrimaryRank(playerDoc);
                return parseRank(rankStr, player.getUsername(), logger);
            } catch (Exception e) {
                logger.error("Error getting rank for {}: {}", player.getUsername(), e.getMessage());
                return Rank.DEFAULT;
            }
        });
    }
    
    /**
     * Gets the effective rank of a CommandSource.
     * Console is treated as STAFF.
     * 
     * @param source the command source
     * @param dataAPI the DataAPI instance
     * @param logger the logger for debugging
     * @return CompletableFuture with the source's rank
     */
    public static CompletableFuture<Rank> getEffectiveRank(CommandSource source, DataAPI dataAPI, Logger logger) {
        if (source instanceof ConsoleCommandSource) {
            return CompletableFuture.completedFuture(Rank.STAFF);
        }
        
        if (source instanceof Player) {
            return getEffectiveRank((Player) source, dataAPI, logger);
        }
        
        return CompletableFuture.completedFuture(Rank.DEFAULT);
    }
    
    private static String resolvePrimaryRank(Document playerDoc) {
        String primary = playerDoc.get("rankInfo.primary", null);
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        String fallback = playerDoc.get("rank", null);
        if (fallback == null || fallback.isBlank()) {
            return Rank.DEFAULT.name();
        }
        return fallback;
    }
    
    private static Rank parseRank(String rawRank, String username, Logger logger) {
        String candidate = (rawRank == null || rawRank.isBlank()) ? Rank.DEFAULT.name() : rawRank;
        try {
            return Rank.valueOf(candidate.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid rank '{}' for player {}, defaulting to DEFAULT", candidate, username);
            return Rank.DEFAULT;
        }
    }
}
