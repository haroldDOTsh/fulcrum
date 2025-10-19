package sh.harold.fulcrum.minigame.team;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

public final class TeamServiceImpl implements TeamService {

    private final TeamRegistry registry = new TeamRegistry();
    private final TeamScoreboardBridge scoreboardBridge;
    private int defaultTeamSize = 1;

    public TeamServiceImpl(UUID matchId) {
        this.scoreboardBridge = new TeamScoreboardBridge(matchId);
    }

    @Override
    public void initialize(TeamPlanner.TeamPlan plan) {
        registry.clear();
        if (plan == null || plan.slots().isEmpty()) {
            return;
        }

        plan.slots().forEach(slot -> {
            Component display = slot.displayName() != null ? slot.displayName() : Component.text(slot.id(), NamedTextColor.WHITE);
            MatchTeam team = new MatchTeam(slot.id(), display, slot.color(), slot.maxMembers());
            registry.registerTeam(team);
            slot.members().forEach(member -> {
                registry.assign(team.getId(), member);
                scoreboardBridge.assign(team, member);
            });
        });

        defaultTeamSize = plan.slots().get(0).maxMembers();
    }

    @Override
    public Optional<MatchTeam> team(UUID playerId) {
        return registry.teamOf(playerId);
    }

    @Override
    public Collection<MatchTeam> teams() {
        return registry.teams();
    }

    @Override
    public TeamData data(String teamId) {
        return registry.teamById(teamId)
                .map(MatchTeam::data)
                .orElseGet(TeamData::new);
    }

    @Override
    public void assignSolo(UUID playerId) {
        if (playerId == null) {
            return;
        }
        if (registry.teamOf(playerId).isPresent()) {
            return;
        }
        MatchTeam target = registry.teams().stream()
                .filter(MatchTeam::hasRoom)
                .min(Comparator.comparingInt(MatchTeam::size))
                .orElseGet(() -> registry.teams().stream()
                        .min(Comparator.comparingInt(MatchTeam::size))
                        .orElse(null));
        if (target == null) {
            // No teams registered yet; create a fallback solo team.
            String id = "team-" + UUID.randomUUID();
            MatchTeam team = new MatchTeam(id, Component.text("Team"), NamedTextColor.WHITE, defaultTeamSize);
            registry.registerTeam(team);
            target = team;
        }
        if (registry.assign(target.getId(), playerId)) {
            scoreboardBridge.assign(target, playerId);
        }
    }

    @Override
    public void removePlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        registry.teamOf(playerId).ifPresent(team -> {
            registry.unassign(playerId);
            scoreboardBridge.remove(playerId);
        });
    }

    @Override
    public void ensureMembership(UUID playerId) {
        if (playerId == null) {
            return;
        }
        registry.teamOf(playerId).ifPresent(team -> scoreboardBridge.assign(team, playerId));
    }

    @Override
    public void teardown() {
        registry.teams().forEach(team -> team.members().forEach(scoreboardBridge::remove));
        registry.clear();
        scoreboardBridge.teardown();
    }
}
