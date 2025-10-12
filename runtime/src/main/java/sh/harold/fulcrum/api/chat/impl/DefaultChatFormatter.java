package sh.harold.fulcrum.api.chat.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.ChatFormatter;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;

/**
 * Default Hypixel-style formatter.
 * Format: [RANK] Username: message
 * Supports & color codes through Adventure API's LegacyComponentSerializer
 */
public class DefaultChatFormatter implements ChatFormatter {
    private final RankService rankService;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public DefaultChatFormatter(RankService rankService) {
        this.rankService = rankService;
    }

    @Override
    public Component format(Player player, Component message) {
        // Get player's effective rank (sync method is safe in async context)
        Rank rank = rankService.getEffectiveRankSync(player.getUniqueId());

        Component formatted = Component.empty();

        // Add rank prefix if not default
        if (rank != Rank.DEFAULT && !rank.getFullPrefix().isEmpty()) {
            // Parse the rank prefix with & color codes using Adventure API
            Component rankComponent = legacySerializer.deserialize(rank.getFullPrefix());
            formatted = formatted.append(rankComponent).append(Component.space());
        }

        // Add player name with rank color
        Component nameComponent = Component.text(player.getName())
                .color(rank.getNameColor());
        formatted = formatted.append(nameComponent);

        // Add separator
        formatted = formatted.append(Component.text(": ", NamedTextColor.GRAY));

        // Add message
        formatted = formatted.append(message);

        return formatted;
    }
}