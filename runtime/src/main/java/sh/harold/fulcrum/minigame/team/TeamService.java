package sh.harold.fulcrum.minigame.team;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TeamService {

    void initialize(TeamPlanner.TeamPlan plan);

    Optional<MatchTeam> team(UUID playerId);

    Collection<MatchTeam> teams();

    TeamData data(String teamId);

    void assignSolo(UUID playerId);

    void removePlayer(UUID playerId);

    void ensureMembership(UUID playerId);

    void teardown();
}
