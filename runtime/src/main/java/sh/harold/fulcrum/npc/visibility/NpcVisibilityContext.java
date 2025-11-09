package sh.harold.fulcrum.npc.visibility;

import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.Set;
import java.util.UUID;

/**
 * Context provided to visibility predicates.
 */
public interface NpcVisibilityContext {
    UUID playerId();

    Player player();

    Rank primaryRank();

    Set<Rank> ranks();

    PlayerSessionRecord playerState();

    NpcDefinition definition();
}
