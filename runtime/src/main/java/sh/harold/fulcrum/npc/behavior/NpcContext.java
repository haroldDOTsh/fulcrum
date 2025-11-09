package sh.harold.fulcrum.npc.behavior;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.npc.NpcDefinition;

import java.util.Collection;
import java.util.UUID;

/**
 * Base context shared between passive + interaction callbacks.
 */
public interface NpcContext {
    UUID npcInstanceId();

    NpcDefinition definition();

    Location location();

    Collection<Player> viewers();

    NpcInteractionHelpers helpers();
}
