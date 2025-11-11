package sh.harold.fulcrum.npc.behavior;

import org.bukkit.entity.Player;
import sh.harold.fulcrum.common.cooldown.CooldownKey;

import java.util.UUID;

/**
 * Context passed when a player interacts with an NPC.
 */
public interface InteractionContext extends PassiveContext {
    Player player();

    UUID playerId();

    /**
     * Returns the shared cooldown key backing this interaction, if any.
     */
    default CooldownKey cooldownKey() {
        return null;
    }
}
