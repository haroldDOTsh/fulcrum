package sh.harold.fulcrum.fundamentals.rank;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.events.RankExpirationEvent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Background task that periodically checks for and removes expired monthly ranks.
 * Runs every hour to ensure expired ranks are cleaned up promptly.
 */
public class RankExpirationTask extends BukkitRunnable {

    // Run every hour (20 ticks per second * 60 seconds * 60 minutes = 72000 ticks)
    private static final long TASK_INTERVAL_TICKS = 72000L;
    private final JavaPlugin plugin;
    private final RankService rankService;

    public RankExpirationTask(JavaPlugin plugin) {
        this.plugin = plugin;
        this.rankService = RankFeature.getRankService();
    }

    /**
     * Start the expiration task with the configured interval.
     */
    public void start() {
        // Run immediately and then every hour
        this.runTaskTimerAsynchronously(plugin, 0L, TASK_INTERVAL_TICKS);
        plugin.getLogger().info("[RankExpirationTask] Started monthly rank expiration task (runs every hour)");
    }

    @Override
    public void run() {
        try {
            plugin.getLogger().info("[RankExpirationTask] Running monthly rank expiration check...");

            // Get all players with active monthly ranks and check for expiration
            rankService.getPlayersWithActiveMonthlyRank().thenAccept(playerIds -> {
                if (playerIds.isEmpty()) {
                    plugin.getLogger().fine("[RankExpirationTask] No players with active monthly ranks found");
                    return;
                }

                plugin.getLogger().info("[RankExpirationTask] Checking " + playerIds.size() + " players with active monthly ranks");

                // Check each player's monthly rank for expiration
                CompletableFuture<Void> allChecks = CompletableFuture.allOf(
                        playerIds.stream()
                                .map(this::checkPlayerExpiration)
                                .toArray(CompletableFuture[]::new)
                );

                allChecks.whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "[RankExpirationTask] Error during expiration check", throwable);
                    } else {
                        plugin.getLogger().info("[RankExpirationTask] Completed monthly rank expiration check");
                    }
                });

            }).exceptionally(throwable -> {
                plugin.getLogger().log(Level.WARNING, "[RankExpirationTask] Failed to get players with active monthly ranks", throwable);
                return null;
            });

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[RankExpirationTask] Unexpected error in expiration task", e);
        }
    }

    /**
     * Check if a specific player's monthly rank has expired and handle it.
     */
    private CompletableFuture<Void> checkPlayerExpiration(UUID playerId) {
        return rankService.getMonthlyRank(playerId).thenCompose(monthlyRank -> {
            // If no monthly rank, nothing to check
            if (monthlyRank == null) {
                return CompletableFuture.completedFuture(null);
            }

            // Check if the monthly rank has expired by attempting to get the effective rank
            // The RankManager should automatically detect expired ranks during calculation
            return rankService.getEffectiveRank(playerId).thenCompose(effectiveRank -> {
                // If the effective rank no longer includes the monthly rank, it has expired
                if (effectiveRank.monthly() == null && monthlyRank != null) {
                    plugin.getLogger().info("[RankExpirationTask] Monthly rank expired for player: " + playerId);

                    // Fire expiration event
                    RankExpirationEvent expirationEvent = new RankExpirationEvent(playerId, monthlyRank, System.currentTimeMillis(), false);

                    // Fire event on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Bukkit.getPluginManager().callEvent(expirationEvent);
                    });

                    // Clean up the expired rank data
                    return rankService.removeMonthlyRank(playerId);
                }

                return CompletableFuture.completedFuture(null);
            });

        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "[RankExpirationTask] Error checking expiration for player: " + playerId, throwable);
            return null;
        });
    }

    /**
     * Stop the expiration task.
     */
    public void stop() {
        if (!this.isCancelled()) {
            this.cancel();
            plugin.getLogger().info("[RankExpirationTask] Stopped monthly rank expiration task");
        }
    }

    /**
     * Run an immediate expiration check (useful for manual cleanup).
     */
    public CompletableFuture<Void> runImmediateCheck() {
        plugin.getLogger().info("[RankExpirationTask] Running immediate expiration check...");

        return CompletableFuture.runAsync(() -> {
            try {
                this.run();
            } catch (Exception e) {
                throw new RuntimeException("Failed to run immediate expiration check", e);
            }
        });
    }
}