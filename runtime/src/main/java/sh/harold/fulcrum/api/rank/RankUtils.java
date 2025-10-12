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
        RankService rankService = ServiceLocatorImpl.getInstance().findService(RankService.class).orElse(null);
        if (rankService == null) {
            logger.warning("[DEBUG] RankService not available! Denying access for " + player.getName());
            return false;
        }
        
        try {
            Rank playerRank = rankService.getEffectiveRankSync(player.getUniqueId());
            boolean hasPermission = playerRank.getPriority() >= requiredRank.getPriority();
            
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
            return true;
        }
        
        if (sender instanceof Player player) {
            return hasRankOrHigher(player, requiredRank);
        }
        
        // Non-player, non-console senders (e.g., command blocks) are treated as admin
        return true;
    }
    
    /**
     * Checks if a player is staff (HELPER, STAFF, or higher).
     * This includes any rank with STAFF category or higher priority than HELPER.
     *
     * @param player the player to check
     * @return true if the player is staff, false otherwise
     */
    public static boolean isStaff(Player player) {
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
     * @param rankId the rank ID to check (e.g., "DONATOR_4", "STAFF")
     * @return true if the player has the specific rank, false otherwise
     */
    public static boolean isRank(Player player, String rankId) {
        if (rankId == null || rankId.isEmpty()) {
            logger.warning("[DEBUG] isRank check with null/empty rankId for " + player.getName());
            return false;
        }
        
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
     * Checks if a player is an admin (STAFF rank).
     *
     * @param player the player to check
     * @return true if the player is admin, false otherwise
     */
    public static boolean isAdmin(Player player) {
        return hasRankOrHigher(player, Rank.STAFF);
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
            return true;
        }
        
        if (sender instanceof Player player) {
            boolean isAdmin = isAdmin(player);
            return isAdmin;
        }
        
        // Command blocks are treated as staff-equivalent
        return true;
    }
    
    /**
     * Checks if a CommandSender can manage ranks.
     * Only STAFF rank and console can manage ranks.
     * 
     * @param sender the command sender to check
     * @return true if the sender can manage ranks, false otherwise
     */
    public static boolean canManageRanks(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        
        if (sender instanceof Player player) {
            boolean canManage = hasRankOrHigher(player, Rank.STAFF);
            return canManage;
        }
        
        // Command blocks cannot manage ranks
        return false;
    }
    
    /**
     * Gets the effective rank of a CommandSender.
     * Console and command blocks are treated as STAFF.
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
        
        // Console and command blocks are treated as STAFF-level
        return Rank.STAFF;
    }
}
