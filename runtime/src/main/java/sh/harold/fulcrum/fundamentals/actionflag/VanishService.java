package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import sh.harold.fulcrum.fundamentals.actionflag.event.PlayerRevealedEvent;
import sh.harold.fulcrum.fundamentals.actionflag.event.PlayerVanishedEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies packet-level vanish to players by hiding them from others and controlling scoreboard state.
 */
public final class VanishService implements Listener {
    private static final String VANISH_TEAM_ID = "fulcrum_vanish";

    private final JavaPlugin plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> previousPickupState = new ConcurrentHashMap<>();
    private final Map<UUID, String> previousTeams = new ConcurrentHashMap<>();

    public VanishService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        vanished.clear();
        previousPickupState.clear();
        previousTeams.clear();
    }

    public void vanish(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        vanished.add(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            applyVanish(player);
        }
    }

    public void reveal(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        boolean wasVanished = vanished.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (!wasVanished && player == null) {
            return;
        }
        if (player != null) {
            revealPlayer(player);
        }
    }

    public boolean isVanished(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return vanished.contains(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        UUID joiningId = joining.getUniqueId();
        if (vanished.contains(joiningId)) {
            applyVanish(joining);
        }
        for (UUID vanishedId : vanished) {
            if (vanishedId.equals(joiningId)) {
                continue;
            }
            Player vanishedPlayer = Bukkit.getPlayer(vanishedId);
            if (vanishedPlayer != null) {
                joining.hidePlayer(plugin, vanishedPlayer);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        previousPickupState.remove(player.getUniqueId());
        removeFromVanishTeam(player);
    }

    private void applyVanish(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                viewer.hidePlayer(plugin, player);
            }
        }
        previousPickupState.put(player.getUniqueId(), player.getCanPickupItems());
        player.setCanPickupItems(false);
        addToVanishTeam(player);
        Bukkit.getPluginManager().callEvent(new PlayerVanishedEvent(player));
    }

    private void revealPlayer(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showPlayer(plugin, player);
        }
        Boolean previousPickup = previousPickupState.remove(player.getUniqueId());
        if (previousPickup != null) {
            player.setCanPickupItems(previousPickup);
        } else {
            player.setCanPickupItems(true);
        }
        removeFromVanishTeam(player);
        Bukkit.getPluginManager().callEvent(new PlayerRevealedEvent(player));
    }

    private void addToVanishTeam(Player player) {
        Scoreboard scoreboard = resolveScoreboard();
        if (scoreboard == null) {
            return;
        }
        Team vanishTeam = ensureVanishTeam(scoreboard);
        String entry = player.getName();
        Team current = scoreboard.getEntryTeam(entry);
        if (current != null && !current.getName().equals(vanishTeam.getName())) {
            previousTeams.put(player.getUniqueId(), current.getName());
            current.removeEntry(entry);
        }
        if (!vanishTeam.hasEntry(entry)) {
            vanishTeam.addEntry(entry);
        }
    }

    private void removeFromVanishTeam(Player player) {
        Scoreboard scoreboard = resolveScoreboard();
        if (scoreboard == null) {
            return;
        }
        String entry = player.getName();
        Team vanishTeam = scoreboard.getTeam(VANISH_TEAM_ID);
        if (vanishTeam != null) {
            vanishTeam.removeEntry(entry);
        }
        String previous = previousTeams.remove(player.getUniqueId());
        if (previous != null) {
            Team previousTeam = scoreboard.getTeam(previous);
            if (previousTeam != null && !previousTeam.hasEntry(entry)) {
                previousTeam.addEntry(entry);
            }
        }
    }

    private Scoreboard resolveScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        return manager != null ? manager.getMainScoreboard() : null;
    }

    private Team ensureVanishTeam(Scoreboard scoreboard) {
        Team team = scoreboard.getTeam(VANISH_TEAM_ID);
        if (team != null) {
            return team;
        }
        team = scoreboard.registerNewTeam(VANISH_TEAM_ID);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        team.setCanSeeFriendlyInvisibles(false);
        return team;
    }
}
