package sh.harold.fulcrum.api.chat;

import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

/**
 * Simple interface for chat formatting logic.
 * Implementations should be thread-safe for async chat events.
 */
public interface ChatFormatter {
    /**
     * Formats a chat message for the given player.
     * Called asynchronously from Paper's AsyncChatEvent.
     * 
     * @param player The player sending the message
     * @param message The raw message content as Component
     * @return Formatted message as Adventure Component
     */
    Component format(Player player, Component message);
}