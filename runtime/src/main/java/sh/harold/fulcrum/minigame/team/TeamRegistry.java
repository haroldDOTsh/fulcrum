package sh.harold.fulcrum.minigame.team;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks match teams and player membership.
 */
public final class TeamRegistry {

    private final Map<String, MatchTeam> teams = new ConcurrentHashMap<>();
    private final Map<UUID, String> membership = new ConcurrentHashMap<>();

    public void registerTeam(MatchTeam team) {
        if (team == null) {
            return;
        }
        teams.put(team.getId(), team);
    }

    public Collection<MatchTeam> teams() {
        return teams.values();
    }

    public Optional<MatchTeam> teamById(String teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(teams.get(teamId));
    }

    public Optional<MatchTeam> teamOf(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        String teamId = membership.get(playerId);
        if (teamId == null) {
            return Optional.empty();
        }
        return teamById(teamId);
    }

    public boolean assign(String teamId, UUID playerId) {
        if (teamId == null || playerId == null) {
            return false;
        }
        MatchTeam team = teams.get(teamId);
        if (team == null) {
            return false;
        }
        if (!team.addMember(playerId)) {
            return false;
        }
        membership.put(playerId, teamId);
        return true;
    }

    public void unassign(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String teamId = membership.remove(playerId);
        if (teamId == null) {
            return;
        }
        MatchTeam team = teams.get(teamId);
        if (team != null) {
            team.removeMember(playerId);
        }
    }

    public void clear() {
        membership.clear();
        teams.clear();
    }
}
