package sh.harold.fulcrum.fundamentals.rank;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import sh.harold.fulcrum.api.rank.events.RankChangeEvent;
import sh.harold.fulcrum.api.rank.events.RankExpirationEvent;

/**
 * Event listener for rank-related events.
 * Handles updating player displays when ranks change or players join/leave.
 */
public class RankEventListener implements Listener {

    private final RankDisplayManager displayManager;

    public RankEventListener(RankDisplayManager displayManager) {
        this.displayManager = displayManager;
    }

    /**
     * Handle player join events to apply rank display immediately.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Update the player's tablist display after they join
        // Use a slight delay to ensure the player is fully loaded
        displayManager.updatePlayerTablist(event.getPlayer().getUniqueId());
    }

    /**
     * Handle player quit events for cleanup if needed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // No cleanup needed currently, but this can be extended later
        // if we need to handle any rank-related cleanup on quit
    }

    /**
     * Handle rank change events to update displays immediately.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRankChange(RankChangeEvent event) {
        // Update the player's display when their rank changes
        displayManager.updatePlayerTablist(event.getPlayerId()).thenRun(() -> {
            // Log successful rank display update if needed
            // This runs after the tablist update completes
        });
    }

    /**
     * Handle rank expiration events to update displays.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRankExpiration(RankExpirationEvent event) {
        // Update the player's display when their monthly rank expires
        displayManager.updatePlayerTablist(event.getPlayerId()).thenRun(() -> {
            // Log successful rank display update after expiration if needed
        });
    }
}