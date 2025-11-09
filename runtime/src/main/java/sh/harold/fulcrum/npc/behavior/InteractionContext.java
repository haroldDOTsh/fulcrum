package sh.harold.fulcrum.npc.behavior;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Context passed when a player interacts with an NPC.
 */
public interface InteractionContext extends PassiveContext {
    Player player();

    UUID playerId();
}
