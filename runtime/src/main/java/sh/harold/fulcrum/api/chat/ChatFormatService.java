package sh.harold.fulcrum.api.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Simple service for chat formatting.
 * Each server instance has exactly one active formatter.
 */
public interface ChatFormatService {
    /**
     * Format a chat message using the current formatter.
     * Thread-safe for async chat events.
     *
     * @param player  The player sending the message
     * @param message The raw message content
     * @return The formatted message
     */
    Component formatMessage(Player player, Component message);

    /**
     * Get the current formatter.
     *
     * @return The current formatter
     */
    ChatFormatter getFormatter();

    /**
     * Set the formatter for this server.
     * Replaces any existing formatter.
     * Pass null to use default formatter.
     *
     * @param formatter The formatter to use, or null for default
     */
    void setFormatter(ChatFormatter formatter);
}