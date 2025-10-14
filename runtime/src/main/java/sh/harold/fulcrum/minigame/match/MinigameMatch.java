package sh.harold.fulcrum.minigame.match;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagContexts;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.minigame.MinigameBlueprint;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;
import sh.harold.fulcrum.minigame.state.machine.StateMachine;

import java.util.*;
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
                         Consumer<String> stateListener,
                         ActionFlagService actionFlags) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.registration = registration;
        this.roster = new RosterManager();

        this.context = new StateContext(plugin, matchId, connectedPlayers, null, roster, registration, actionFlags);
        this.machine = new StateMachine(plugin, matchId, blueprint.getStateGraph(), context, blueprint.getStartStateId(), stateListener);
        this.context.bind(machine);
        this.context.applyFlagContext(ActionFlagContexts.MATCH_PREGAME_DEFAULT);

        for (Player player : initialPlayers) {
            addPlayer(player, false);
        }
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
        UUID playerId = player.getUniqueId();
        boolean firstConnection = connectedPlayers.add(playerId);
        roster.addPlayer(playerId, respawnAllowed);
        RosterManager.Entry entry = roster.get(playerId);
        if (entry != null) {
            entry.setRespawnAllowed(respawnAllowed);
        }
        context.registerPlayer(playerId);
        String currentContext = context.getCurrentFlagContext();
        if (currentContext != null && !currentContext.isBlank()) {
            context.applyFlagContext(playerId, currentContext);
        } else {
            context.applyFlagContext(playerId, ActionFlagContexts.MATCH_PREGAME_DEFAULT);
        }
        if (firstConnection) {
            announcePreLobbyJoin(player);
        }
    }

    public void removePlayer(UUID playerId) {
        connectedPlayers.remove(playerId);
        roster.removePlayer(playerId);
        context.clearFlags(playerId);
        context.unregisterPlayer(playerId);
        String playerName = Optional.ofNullable(Bukkit.getPlayer(playerId))
                .map(Player::getName)
                .orElseGet(() -> {
                    String name = Bukkit.getOfflinePlayer(playerId).getName();
                    if (name == null || name.isBlank()) {
                        return playerId.toString().substring(0, 8);
                    }
                    return name;
                });
        announcePreLobbyQuit(playerName);
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

    private void announcePreLobbyJoin(Player player) {
        if (player == null || !isInPreLobby()) {
            return;
        }
        long activeCount = roster.activeCount();
        int maxPlayers = resolveMaxPlayers();
        String display = ChatColor.GOLD + player.getName() + ChatColor.YELLOW;
        String messageBody = " has joined! (" + activeCount + "/" + (maxPlayers > 0 ? maxPlayers : "?") + ")";
        context.broadcast(display + messageBody + ChatColor.RESET);
    }

    private void announcePreLobbyQuit(String playerName) {
        if (!isInPreLobby()) {
            return;
        }
        String display = ChatColor.GOLD + playerName + ChatColor.YELLOW;
        context.broadcast(display + " has quit!" + ChatColor.RESET);
    }

    private boolean isInPreLobby() {
        return MinigameBlueprint.STATE_PRE_LOBBY.equals(context.currentStateId());
    }

    private int resolveMaxPlayers() {
        if (registration != null && registration.getDescriptor() != null) {
            int configured = registration.getDescriptor().getMaxPlayers();
            if (configured > 0) {
                return configured;
            }
        }
        return 0;
    }
}
