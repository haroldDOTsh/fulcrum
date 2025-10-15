package sh.harold.fulcrum.velocity.api.rank;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.session.PlayerSessionRecord;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.Optional;
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
     * @param player       the player to check
     * @param requiredRank the minimum required rank
     * @param dataAPI      the DataAPI instance to query player data
     * @param logger       the logger for debugging
     * @return CompletableFuture with true if the player has the required rank or higher
     */
    public static CompletableFuture<Boolean> hasRankOrHigher(Player player, Rank requiredRank,
                                                             VelocityPlayerSessionService sessionService,
                                                             DataAPI dataAPI, Logger logger) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Checking rank for player {} ({}), requires {}",
                        player.getUsername(), player.getUniqueId(), requiredRank.name());

                Rank playerRank = fetchRank(player, sessionService, dataAPI, logger);
                if (playerRank == null) {
                    logger.debug("Rank data not found for {}, denying access", player.getUsername());
                    return false;
                }

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

    public static CompletableFuture<Boolean> hasRankOrHigher(Player player, Rank requiredRank,
                                                             DataAPI dataAPI, Logger logger) {
        return hasRankOrHigher(player, requiredRank, null, dataAPI, logger);
    }

    /**
     * Synchronously checks if a player has the specified rank or higher.
     * This is a blocking call and should be used carefully.
     *
     * @param player       the player to check
     * @param requiredRank the minimum required rank
     * @param sessionService session cache access
     * @param dataAPI      the DataAPI instance
     * @param logger       the logger for debugging
     * @return true if the player has the required rank or higher
     */
    public static boolean hasRankOrHigherSync(Player player, Rank requiredRank,
                                              VelocityPlayerSessionService sessionService,
                                              DataAPI dataAPI, Logger logger) {
        try {
            return hasRankOrHigher(player, requiredRank, sessionService, dataAPI, logger).get();
        } catch (Exception e) {
            logger.error("Error in synchronous rank check for {}: {}",
                    player.getUsername(), e.getMessage());
            return false;
        }
    }

    public static boolean hasRankOrHigherSync(Player player, Rank requiredRank,
                                              DataAPI dataAPI, Logger logger) {
        return hasRankOrHigherSync(player, requiredRank, null, dataAPI, logger);
    }

    /**
     * Checks if a CommandSource has the specified rank or higher.
     * Console is always treated as having admin privileges.
     *
     * @param source       the command source to check
     * @param requiredRank the minimum required rank
     * @param dataAPI      the DataAPI instance
     * @param logger       the logger for debugging
     * @return CompletableFuture with true if the source has the required rank or higher
     */
    public static CompletableFuture<Boolean> hasRankOrHigher(CommandSource source, Rank requiredRank,
                                                             VelocityPlayerSessionService sessionService,
                                                             DataAPI dataAPI, Logger logger) {
        // Console always has admin privileges
        if (source instanceof ConsoleCommandSource) {
            logger.debug("Permission check for console - always allowed");
            return CompletableFuture.completedFuture(true);
        }

        if (source instanceof Player) {
            return hasRankOrHigher((Player) source, requiredRank, sessionService, dataAPI, logger);
        }

        // Unknown source type - deny by default
        logger.debug("Unknown command source type: {}", source.getClass().getName());
        return CompletableFuture.completedFuture(false);
    }

    public static CompletableFuture<Boolean> hasRankOrHigher(CommandSource source, Rank requiredRank,
                                                             DataAPI dataAPI, Logger logger) {
        return hasRankOrHigher(source, requiredRank, null, dataAPI, logger);
    }

    /**
     * Checks if a player is admin (STAFF rank).
     *
     * @param player  the player to check
     * @param dataAPI the DataAPI instance
     * @param logger  the logger for debugging
     * @return CompletableFuture with true if the player is admin
     */
    public static CompletableFuture<Boolean> isAdmin(Player player,
                                                     VelocityPlayerSessionService sessionService,
                                                     DataAPI dataAPI, Logger logger) {
        return hasRankOrHigher(player, Rank.STAFF, sessionService, dataAPI, logger);
    }

    public static CompletableFuture<Boolean> isAdmin(Player player,
                                                     DataAPI dataAPI, Logger logger) {
        return isAdmin(player, null, dataAPI, logger);
    }

    /**
     * Checks if a CommandSource is admin (STAFF).
     * Console is always treated as admin.
     *
     * @param source  the command source to check
     * @param dataAPI the DataAPI instance
     * @param logger  the logger for debugging
     * @return CompletableFuture with true if the source is admin
     */
    public static CompletableFuture<Boolean> isAdmin(CommandSource source,
                                                     VelocityPlayerSessionService sessionService,
                                                     DataAPI dataAPI, Logger logger) {
        if (source instanceof ConsoleCommandSource) {
            return CompletableFuture.completedFuture(true);
        }

        if (source instanceof Player) {
            return isAdmin((Player) source, sessionService, dataAPI, logger);
        }

        return CompletableFuture.completedFuture(false);
    }

    public static CompletableFuture<Boolean> isAdmin(CommandSource source,
                                                     DataAPI dataAPI, Logger logger) {
        return isAdmin(source, null, dataAPI, logger);
    }

    /**
     * Gets the effective rank of a Player asynchronously.
     *
     * @param player  the player
     * @param dataAPI the DataAPI instance
     * @param logger  the logger for debugging
     * @return CompletableFuture with the player's rank
     */
    public static CompletableFuture<Rank> getEffectiveRank(Player player,
                                                           VelocityPlayerSessionService sessionService,
                                                           DataAPI dataAPI, Logger logger) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Rank rank = fetchRank(player, sessionService, dataAPI, logger);
                return rank != null ? rank : Rank.DEFAULT;
            } catch (Exception e) {
                logger.error("Error getting rank for {}: {}", player.getUsername(), e.getMessage());
                return Rank.DEFAULT;
            }
        });
    }

    public static CompletableFuture<Rank> getEffectiveRank(Player player,
                                                           DataAPI dataAPI, Logger logger) {
        return getEffectiveRank(player, null, dataAPI, logger);
    }

    /**
     * Gets the effective rank of a CommandSource.
     * Console is treated as STAFF.
     *
     * @param source  the command source
     * @param dataAPI the DataAPI instance
     * @param logger  the logger for debugging
     * @return CompletableFuture with the source's rank
     */
    public static CompletableFuture<Rank> getEffectiveRank(CommandSource source,
                                                           VelocityPlayerSessionService sessionService,
                                                           DataAPI dataAPI, Logger logger) {
        if (source instanceof ConsoleCommandSource) {
            return CompletableFuture.completedFuture(Rank.STAFF);
        }

        if (source instanceof Player) {
            return getEffectiveRank((Player) source, sessionService, dataAPI, logger);
        }

        return CompletableFuture.completedFuture(Rank.DEFAULT);
    }

    public static CompletableFuture<Rank> getEffectiveRank(CommandSource source,
                                                           DataAPI dataAPI, Logger logger) {
        return getEffectiveRank(source, null, dataAPI, logger);
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

    private static Rank fetchRank(Player player,
                                  VelocityPlayerSessionService sessionService,
                                  DataAPI dataAPI,
                                  Logger logger) {
        if (sessionService != null) {
            Optional<PlayerSessionRecord> session = sessionService.getSession(player.getUniqueId());
            if (session.isPresent()) {
                Rank fromSession = parseRank(resolvePrimaryRank(session.get()), player.getUsername(), logger);
                if (fromSession != null) {
                    return fromSession;
                }
            }
        }

        Document document = dataAPI.collection("players").document(player.getUniqueId().toString());
        if (!document.exists()) {
            return null;
        }
        return parseRank(resolvePrimaryRank(document), player.getUsername(), logger);
    }

    private static String resolvePrimaryRank(PlayerSessionRecord record) {
        Object primary = record.getRank().get("primary");
        if (primary instanceof String primaryName && !primaryName.isBlank()) {
            return primaryName;
        }
        Object fallback = record.getCore().get("rank");
        return fallback != null ? fallback.toString() : null;
    }
}
