package sh.harold.fulcrum.minigame.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import sh.harold.fulcrum.minigame.MinigameAttributes;
import sh.harold.fulcrum.minigame.MinigameBlueprint;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.environment.MinigameEnvironmentService.MatchEnvironment;
import sh.harold.fulcrum.minigame.match.MinigameMatch;
import sh.harold.fulcrum.minigame.state.context.StateContext;

import java.util.Optional;
import java.util.UUID;

/**
 * Ensures lethal damage inside a minigame is routed through the elimination pipeline
 * instead of triggering the vanilla death flow.
 */
public final class MatchDamageListener implements Listener {
    private static final double VOID_ELIMINATION_Y = -64.0D;
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

        if (handleElimination(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidFall(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getY() >= VOID_ELIMINATION_Y) {
            return;
        }

        Location from = event.getFrom();
        if (from != null && from.getY() < VOID_ELIMINATION_Y) {
            // Already below the threshold; avoid double-processing while falling.
            return;
        }

        handleElimination(event.getPlayer());
    }

    private boolean handleElimination(Player player) {
        UUID playerId = player.getUniqueId();
        Optional<MinigameMatch> matchOpt = engine.findMatchByPlayer(playerId);
        if (matchOpt.isEmpty()) {
            return false;
        }

        MinigameMatch match = matchOpt.get();
        StateContext context = match.getContext();
        if (!isPlayerWithinMatchEnvironment(player, match)) {
            return false;
        }

        String stateId = context.currentStateId();
        boolean matchComplete = context.getAttributeOptional(MinigameAttributes.MATCH_COMPLETE, Boolean.class).orElse(Boolean.FALSE);
        boolean inPreLobby = MinigameBlueprint.STATE_PRE_LOBBY.equals(stateId);
        boolean inPostGame = MinigameBlueprint.STATE_END_GAME.equals(stateId) || matchComplete;

        player.setFallDistance(0.0F);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setHealth(player.getMaxHealth());

        if (inPreLobby || inPostGame) {
            context.teleportPlayerToDefaultSpawn(playerId);
            return true;
        }

        boolean allowRespawn = context.isRespawnAllowed(playerId);
        context.eliminatePlayer(playerId, allowRespawn, 0L);
        return true;
    }

    private boolean isPlayerWithinMatchEnvironment(Player player, MinigameMatch match) {
        if (player == null || match == null) {
            return false;
        }
        StateContext context = match.getContext();
        if (context == null) {
            return true;
        }
        MatchEnvironment environment = context.getAttributeOptional(MinigameAttributes.MATCH_ENVIRONMENT, MatchEnvironment.class)
                .orElse(null);
        if (environment == null) {
            return true;
        }
        String worldName = environment.worldName();
        if (worldName == null || worldName.isBlank()) {
            return true;
        }
        return player.getWorld() != null && worldName.equalsIgnoreCase(player.getWorld().getName());
    }
}
