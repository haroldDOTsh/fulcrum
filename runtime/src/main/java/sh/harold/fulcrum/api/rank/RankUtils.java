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
            logger.warning("RankService not available, denying access");
            return false;
        }
        
        Rank playerRank = rankService.getEffectiveRankSync(player.getUniqueId());
        return playerRank.getPriority() >= requiredRank.getPriority();
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
     * Checks if a player is staff (HELPER, MODERATOR, or ADMIN).
     * 
     * @param player the player to check
     * @return true if the player is staff, false otherwise
     */
    public static boolean isStaff(Player player) {
        return hasRankOrHigher(player, Rank.HELPER);
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
     * Checks if a player is an admin (ADMIN or MODERATOR).
     * 
     * @param player the player to check
     * @return true if the player is admin, false otherwise
     */
    public static boolean isAdmin(Player player) {
        return hasRankOrHigher(player, Rank.MODERATOR);
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
            return isAdmin(player);
        }
        
        // Command blocks are treated as admin
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
            return true;
        }
        
        if (sender instanceof Player player) {
            return hasRankOrHigher(player, Rank.ADMIN);
        }
        
        // Command blocks cannot manage ranks
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