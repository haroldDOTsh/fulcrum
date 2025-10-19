package sh.harold.fulcrum.minigame.team;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class TeamScoreboardBridge {

    private final String prefix;
    private final Map<String, String> teamNames = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    TeamScoreboardBridge(UUID matchId) {
        String base = matchId.toString().replace("-", "");
        String trimmed = base.length() > 8 ? base.substring(0, 8) : base;
        this.prefix = "FT" + trimmed;
    }

    void assign(MatchTeam team, UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        Scoreboard scoreboard = player.getScoreboard();
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(scoreboard);
        }

        String teamName = teamNames.computeIfAbsent(team.getId(), key -> nextTeamName());
        Team scoreboardTeam = scoreboard.getTeam(teamName);
        if (scoreboardTeam == null) {
            scoreboardTeam = scoreboard.registerNewTeam(teamName);
            scoreboardTeam.color(team.getColor());
            scoreboardTeam.setAllowFriendlyFire(false);
            scoreboardTeam.setCanSeeFriendlyInvisibles(true);
            scoreboardTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        } else {
            scoreboardTeam.color(team.getColor());
        }

        for (Team existing : scoreboard.getTeams()) {
            if (existing.getName().startsWith(prefix) && !existing.getName().equals(teamName)) {
                existing.removeEntry(player.getName());
            }
        }

        scoreboardTeam.addEntry(player.getName());
    }

    void remove(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        Scoreboard scoreboard = player.getScoreboard();
        if (scoreboard == null) {
            return;
        }
        new ArrayList<>(scoreboard.getTeams()).forEach(team -> {
            if (team.getName().startsWith(prefix)) {
                team.removeEntry(player.getName());
            }
        });
    }

    void teardown() {
        Bukkit.getOnlinePlayers().forEach(player -> {
            Scoreboard scoreboard = player.getScoreboard();
            if (scoreboard == null) {
                return;
            }
            new ArrayList<>(scoreboard.getTeams()).forEach(team -> {
                if (team.getName().startsWith(prefix)) {
                    team.unregister();
                }
            });
        });
        teamNames.clear();
        counter.set(0);
    }

    private String nextTeamName() {
        return prefix + counter.getAndIncrement();
    }
}
