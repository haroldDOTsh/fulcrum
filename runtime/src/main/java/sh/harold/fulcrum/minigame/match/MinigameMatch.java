package sh.harold.fulcrum.minigame.match;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagContexts;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameBlueprint;
import sh.harold.fulcrum.minigame.MinigameRegistration;
import sh.harold.fulcrum.minigame.defaults.PreLobbyScoreboard;
import sh.harold.fulcrum.minigame.state.context.StateContext;
import sh.harold.fulcrum.minigame.state.event.MinigameEvent;
import sh.harold.fulcrum.minigame.state.machine.StateMachine;
import sh.harold.fulcrum.minigame.team.TeamPlanner;
import sh.harold.fulcrum.minigame.team.TeamService;
import sh.harold.fulcrum.minigame.team.TeamServiceImpl;

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
    private final TeamService teamService;

    public MinigameMatch(JavaPlugin plugin,
                         UUID matchId,
                         MinigameBlueprint blueprint,
                         MinigameRegistration registration,
                         Collection<Player> initialPlayers,
                         Consumer<String> stateListener,
                         ActionFlagService actionFlags,
                         TeamPlanner.TeamPlan teamPlan) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.registration = registration;
        this.roster = new RosterManager();

        this.teamService = new TeamServiceImpl(matchId);
        this.context = new StateContext(plugin, matchId, connectedPlayers, null, roster, registration, actionFlags, teamService);
        this.machine = new StateMachine(plugin, matchId, blueprint.getStateGraph(), context, blueprint.getStartStateId(), stateListener);
        this.context.bind(machine);
        this.context.applyFlagContext(ActionFlagContexts.MATCH_PREGAME_DEFAULT);

        if (teamPlan != null) {
            this.teamService.initialize(teamPlan);
        }

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
        if (teamService != null) {
            if (teamService.team(playerId).isPresent()) {
                teamService.ensureMembership(playerId);
            } else {
                teamService.assignSolo(playerId);
            }
        }
        String currentContext = context.getCurrentFlagContext();
        if (currentContext != null && !currentContext.isBlank()) {
            context.applyFlagContext(playerId, currentContext);
        } else {
            context.applyFlagContext(playerId, ActionFlagContexts.MATCH_PREGAME_DEFAULT);
        }
        PreLobbyScoreboard.showToPlayer(context, playerId);
        if (firstConnection) {
            announcePreLobbyJoin(player);
        }
        PreLobbyScoreboard.refresh(context);
    }

    public void removePlayer(UUID playerId) {
        connectedPlayers.remove(playerId);
        roster.removePlayer(playerId);
        if (teamService != null) {
            teamService.removePlayer(playerId);
        }
        context.clearFlags(playerId);
        context.unregisterPlayer(playerId);
        PreLobbyScoreboard.hideFromPlayer(context, playerId);
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
        PreLobbyScoreboard.refresh(context);
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

    public TeamService getTeamService() {
        return teamService;
    }

    public void shutdown() {
        if (teamService != null) {
            teamService.teardown();
        }
    }

    private void announcePreLobbyJoin(Player player) {
        if (player == null || !isInPreLobby()) {
            return;
        }
        long activeCount = roster.activeCount();
        int maxPlayers = resolveMaxPlayers();
        String playerColor = resolveRankColor(player);
        String display = playerColor + player.getName() + ChatColor.YELLOW;
        String messageBody = " has joined! (" +
                ChatColor.AQUA + activeCount +
                ChatColor.YELLOW + "/" +
                ChatColor.AQUA + (maxPlayers > 0 ? maxPlayers : "?") +
                ChatColor.YELLOW + ")";
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

    private String resolveRankColor(Player player) {
        if (player == null) {
            return ChatColor.GOLD.toString();
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return ChatColor.GOLD.toString();
        }
        RankService rankService = locator.findService(RankService.class).orElse(null);
        if (rankService == null) {
            return ChatColor.GOLD.toString();
        }
        try {
            Rank effectiveRank = rankService.getEffectiveRankSync(player.getUniqueId());
            if (effectiveRank != null) {
                return ChatColor.translateAlternateColorCodes('&', effectiveRank.getColorCode());
            }
        } catch (Exception ignored) {
            // Fall back to default color on any lookup failure.
        }
        return ChatColor.GOLD.toString();
    }
}
