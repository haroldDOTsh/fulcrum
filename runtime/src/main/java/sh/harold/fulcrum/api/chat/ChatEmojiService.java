package sh.harold.fulcrum.api.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Service that replaces emoji tokens (e.g. :heart:) with their unicode representation.
 */
public interface ChatEmojiService {
    /**
     * Applies emoji replacements to the provided message if the player is allowed to use them.
     *
     * @param player  message author
     * @param message original message component
     * @return message after emoji replacements (may be the same instance)
     */
    Component apply(Player player, Component message);
}
