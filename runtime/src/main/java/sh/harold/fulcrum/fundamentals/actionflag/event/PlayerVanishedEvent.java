package sh.harold.fulcrum.fundamentals.actionflag.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired when the vanish service hides a player from others.
 */
public final class PlayerVanishedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public PlayerVanishedEvent(Player player) {
        super(player);
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
