package sh.harold.fulcrum.minigame.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.match.MinigameMatch;
import sh.harold.fulcrum.minigame.state.context.StateContext;

import java.util.Optional;
import java.util.UUID;

/**
 * Ensures lethal damage inside a minigame is routed through the elimination pipeline
 * instead of triggering the vanilla death flow.
 */
public final class MatchDamageListener implements Listener {
    private final MinigameEngine engine;

    public MatchDamageListener(MinigameEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        double finalHealth = player.getHealth() - event.getFinalDamage();
        if (finalHealth > 0.0D) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Optional<MinigameMatch> matchOpt = engine.findMatchByPlayer(playerId);
        if (matchOpt.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        player.setFallDistance(0.0F);
        player.setHealth(Math.max(1.0D, player.getMaxHealth()));
        player.setFoodLevel(20);
        player.setSaturation(20f);

        MinigameMatch match = matchOpt.get();
        StateContext context = match.getContext();
        boolean allowRespawn = context.isRespawnAllowed(playerId);
        context.eliminatePlayer(playerId, allowRespawn, 0L);
    }
}
