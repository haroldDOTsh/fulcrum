package sh.harold.fulcrum.api.rank;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.util.logging.Logger;

/**
 * Utility class for rank-based permission checks.
 * Provides convenient methods to check player ranks and permissions.
 */
public final class RankUtils {
    private static final Logger logger = Logger.getLogger(RankUtils.class.getName());
    
    private RankUtils() {
        // Utility class
    }
    
    /**
     * Checks if a player has the specified rank or higher.
     * 
     * @param player the player to check
     * @param requiredRank the minimum required rank
     * @return true if the player has the required rank or higher, false otherwise
     */
    public static boolean hasRankOrHigher(Player player, Rank requiredRank) {
        logger.info("[DEBUG] Checking rank for player " + player.getName() + ", requires " + requiredRank.name());
        
        RankService rankService = ServiceLocatorImpl.getInstance().findService(RankService.class).orElse(null);
        if (rankService == null) {
            logger.warning("[DEBUG] RankService not available! Denying access for " + player.getName());
            return false;
        }
        
        try {
            Rank playerRank = rankService.getEffectiveRankSync(player.getUniqueId());
            boolean hasPermission = playerRank.getPriority() >= requiredRank.getPriority();
            
            logger.info("[DEBUG] Player " + player.getName() +
                       " has rank " + playerRank.name() + " (priority " + playerRank.getPriority() +
                       "), needs " + requiredRank.name() + " (priority " + requiredRank.getPriority() +
                       ") -> " + (hasPermission ? "ALLOWED" : "DENIED"));
            
            return hasPermission;
        } catch (Exception e) {
            logger.severe("[DEBUG] Error checking rank for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Checks if a CommandSender has the specified rank or higher.
     * Console is always treated as having admin privileges.
     * 
     * @param sender the command sender to check
     * @param requiredRank the minimum required rank
     * @return true if the sender has the required rank or higher, false otherwise
     */
    public static boolean hasRankOrHigher(CommandSender sender, Rank requiredRank) {
        // Console always has admin privileges
        if (sender instanceof ConsoleCommandSender) {
            logger.info("[DEBUG] Permission check for console - always allowed");
            return true;
        }
        
        if (sender instanceof Player player) {
            return hasRankOrHigher(player, requiredRank);
        }
        
        // Non-player, non-console senders (e.g., command blocks) are treated as admin
        logger.info("[DEBUG] Permission check for command block - always allowed");
        return true;
    }
    
    /**
     * Checks if a player is staff (HELPER, MODERATOR, ADMIN, or higher).
     * This includes any rank with STAFF category or higher priority than HELPER.
     *
     * @param player the player to check
     * @return true if the player is staff, false otherwise
     */
    public static boolean isStaff(Player player) {
        logger.info("[DEBUG] isStaff check for player " + player.getName());
        
        RankService rankService = ServiceLocatorImpl.getInstance().findService(RankService.class).orElse(null);
        if (rankService == null) {
            logger.warning("[DEBUG] RankService not available! Denying staff access for " + player.getName());
            return false;
        }
        
        try {
            Rank playerRank = rankService.getEffectiveRankSync(player.getUniqueId());
            
            // Check if rank is in STAFF category or has staff-level priority
            boolean isStaff = playerRank.getCategory() == RankCategory.STAFF ||
                              playerRank.getPriority() >= Rank.HELPER.getPriority();
            
            logger.info("[DEBUG] Player " + player.getName() +
                       " has rank " + playerRank.name() +
                       " (category: " + playerRank.getCategory() +
                       ", priority: " + playerRank.getPriority() +
                       ") -> isStaff: " + isStaff);
            
            return isStaff;
        } catch (Exception e) {
            logger.severe("[DEBUG] Error checking staff status for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Checks if a player has a specific rank by rank ID.
     *
     * @param player the player to check
     * @param rankId the rank ID to check (e.g., "MVP_PLUS_PLUS", "ADMIN")
     * @return true if the player has the specific rank, false otherwise
     */
    public static boolean isRank(Player player, String rankId) {
        if (rankId == null || rankId.isEmpty()) {
            logger.warning("[DEBUG] isRank check with null/empty rankId for " + player.getName());
            return false;
        }
        
        logger.info("[DEBUG] isRank check for player " + player.getName() + ", rank: " + rankId);
        
        RankService rankService = ServiceLocatorImpl.getInstance().findService(RankService.class).orElse(null);
        if (rankService == null) {
            logger.warning("[DEBUG] RankService not available! Denying rank check for " + player.getName());
            return false;
        }
        
        try {
            // Try to parse the rank ID
            Rank targetRank;
            try {
                targetRank = Rank.valueOf(rankId.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warning("[DEBUG] Invalid rank ID: " + rankId);
                return false;
            }
            
            Rank playerRank = rankService.getEffectiveRankSync(player.getUniqueId());
            
            // Check exact match
            boolean hasRank = playerRank == targetRank;
            
            logger.info("[DEBUG] Player " + player.getName() +
                       " has rank " + playerRank.name() +
                       ", checking for " + targetRank.name() +
                       " -> " + hasRank);
            
            return hasRank;
        } catch (Exception e) {
            logger.severe("[DEBUG] Error checking rank for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Checks if a CommandSender is staff.
     * Console is always treated as staff.
     * 
     * @param sender the command sender to check
     * @return true if the sender is staff, false otherwise
     */
    public static boolean isStaff(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        
        if (sender instanceof Player player) {
            return isStaff(player);
        }
        
        // Command blocks are treated as staff
        return true;
    }
    
    /**
     * Checks if a player is an admin (ADMIN rank).
     *
     * @param player the player to check
     * @return true if the player is admin, false otherwise
     */
    public static boolean isAdmin(Player player) {
        logger.info("[DEBUG] isAdmin check for player " + player.getName() + " (requires ADMIN)");
        return hasRankOrHigher(player, Rank.ADMIN);
    }
    
    /**
     * Checks if a CommandSender is an admin.
     * Console is always treated as admin.
     * 
     * @param sender the command sender to check
     * @return true if the sender is admin, false otherwise
     */
    public static boolean isAdmin(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            logger.info("[DEBUG] isAdmin check for console - always true");
            return true;
        }
        
        if (sender instanceof Player player) {
            boolean isAdmin = isAdmin(player);
            logger.info("[DEBUG] isAdmin check for " + player.getName() + " -> " + isAdmin);
            return isAdmin;
        }
        
        // Command blocks are treated as admin
        logger.info("[DEBUG] isAdmin check for command block - always true");
        return true;
    }
    
    /**
     * Checks if a CommandSender can manage ranks.
     * Only ADMIN rank and console can manage ranks.
     * 
     * @param sender the command sender to check
     * @return true if the sender can manage ranks, false otherwise
     */
    public static boolean canManageRanks(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            logger.info("[DEBUG] canManageRanks check for console - always true");
            return true;
        }
        
        if (sender instanceof Player player) {
            logger.info("[DEBUG] canManageRanks check for " + player.getName() + " (requires ADMIN)");
            boolean canManage = hasRankOrHigher(player, Rank.ADMIN);
            logger.info("[DEBUG] canManageRanks result for " + player.getName() + " -> " + canManage);
            return canManage;
        }
        
        // Command blocks cannot manage ranks
        logger.info("[DEBUG] canManageRanks check for command block - false");
        return false;
    }
    
    /**
     * Gets the effective rank of a CommandSender.
     * Console and command blocks are treated as ADMIN.
     * 
     * @param sender the command sender
     * @return the effective rank of the sender
     */
    public static Rank getEffectiveRank(CommandSender sender) {
        if (sender instanceof Player player) {
            RankService rankService = ServiceLocatorImpl.getInstance().findService(RankService.class).orElse(null);
            if (rankService == null) {
                logger.warning("RankService not available, returning DEFAULT rank");
                return Rank.DEFAULT;
            }
            return rankService.getEffectiveRankSync(player.getUniqueId());
        }
        
        // Console and command blocks are treated as ADMIN
        return Rank.ADMIN;
    }
}