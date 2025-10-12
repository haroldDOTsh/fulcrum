package sh.harold.fulcrum.minigame.match;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.minigame.MinigameBlueprint;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;
import sh.harold.fulcrum.minigame.state.machine.StateMachine;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Wraps the state machine and roster for a single match.
 */
public final class MinigameMatch {
    private final UUID matchId;
    private final MinigameBlueprint blueprint;
    private final RosterManager roster;
    private final StateContext context;
    private final StateMachine machine;
    private final MinigameRegistration registration;
    private final Set<UUID> connectedPlayers = ConcurrentHashMap.newKeySet();

    public MinigameMatch(JavaPlugin plugin,
                         UUID matchId,
                         MinigameBlueprint blueprint,
                         MinigameRegistration registration,
                         Collection<Player> initialPlayers,
                         Consumer<String> stateListener) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.registration = registration;
        this.roster = new RosterManager();

        for (Player player : initialPlayers) {
            UUID playerId = player.getUniqueId();
            roster.addPlayer(playerId, true);
            connectedPlayers.add(playerId);
        }

        this.context = new StateContext(plugin, matchId, connectedPlayers, null, roster, registration);
        this.machine = new StateMachine(plugin, matchId, blueprint.getStateGraph(), context, blueprint.getStartStateId(), stateListener);
        this.context.bind(machine);
    }

    public UUID getMatchId() {
        return matchId;
    }

    public void tick() {
        machine.tick();
    }

    public void publishEvent(MinigameEvent event) {
        machine.publishEvent(event);
    }

    public void addPlayer(Player player, boolean respawnAllowed) {
        roster.addPlayer(player.getUniqueId(), respawnAllowed);
        connectedPlayers.add(player.getUniqueId());
    }

    public void removePlayer(UUID playerId) {
        connectedPlayers.remove(playerId);
        roster.removePlayer(playerId);
    }

    public StateContext getContext() {
        return context;
    }

    public RosterManager getRoster() {
        return roster;
    }

    public MinigameBlueprint getBlueprint() {
        return blueprint;
    }
}

