package sh.harold.fulcrum.minigame;

import org.bukkit.entity.Player;
import sh.harold.fulcrum.minigame.routing.PlayerRouteRegistry;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Convenience facade over {@link MinigameEngine} exposing lifecycle operations for modules.
 */
public final class GameManager {
    private final MinigameEngine engine;

    public GameManager(MinigameEngine engine) {
        this.engine = engine;
    }

    public Optional<UUID> startMatch(String familyId, Collection<Player> players) {
        return engine.startMatchForFamily(familyId, players);
    }

    public UUID startAdHocMatch(MinigameBlueprint blueprint, Collection<Player> players) {
        return engine.startAdHocMatch(blueprint, players);
    }

    public void endMatch(UUID matchId) {
        engine.endMatch(matchId);
    }

    public void handleRoutedPlayer(Player player, PlayerRouteRegistry.RouteAssignment assignment) {
        engine.handleRoutedPlayer(player, assignment);
    }

    public void handlePlayerQuit(Player player) {
        engine.handlePlayerQuit(player);
    }

    public void handleLocalRoute(Player player) {
        engine.handleLocalRoute(player);
    }
}
