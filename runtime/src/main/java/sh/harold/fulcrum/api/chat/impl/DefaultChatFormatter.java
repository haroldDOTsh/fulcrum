package sh.harold.fulcrum.api.chat.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.ChatFormatter;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.network.RankInfoView;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;

/**
 * Default Hypixel-style formatter.
 * Format: [RANK] Username: message
 * Supports &amp; color codes through Adventure API's LegacyComponentSerializer
 */
public class DefaultChatFormatter implements ChatFormatter {
    private final RankService rankService;
    private final NetworkConfigService networkConfigService;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public DefaultChatFormatter(RankService rankService, NetworkConfigService networkConfigService) {
        this.rankService = rankService;
        this.networkConfigService = networkConfigService;
    }

    @Override
    public Component format(Player player, Component message) {
        // Get player's effective rank (sync method is safe in async context)
        Rank rank = rankService.getEffectiveRankSync(player.getUniqueId());
        if (rank == null) {
            rank = Rank.DEFAULT;
        }

        Component formatted = Component.empty();

        RankInfoContext rankInfoContext = resolveRankInfo(rank);
        Component tooltipComponent = rankInfoContext.tooltip();
        String infoUrl = rankInfoContext.infoUrl();

        // Add rank prefix if not default
        if (rank != Rank.DEFAULT && !rank.getFullPrefix().isEmpty()) {
            // Parse the rank prefix with & color codes using Adventure API
            Component rankComponent = legacySerializer.deserialize(rank.getFullPrefix());
            rankComponent = applyInteractions(rankComponent, tooltipComponent, infoUrl);
            formatted = formatted.append(rankComponent).append(Component.space());
        }

        // Add player name with rank color
        Component nameComponent = Component.text(player.getName())
                .color(rank.getNameColor());
        nameComponent = applyInteractions(nameComponent, tooltipComponent, infoUrl);
        formatted = formatted.append(nameComponent);

        // Add separator
        formatted = formatted.append(Component.text(": ", NamedTextColor.GRAY));

        // Add message
        formatted = formatted.append(message);

        return formatted;
    }

    private RankInfoContext resolveRankInfo(Rank rank) {
        if (networkConfigService == null || rank == null || !rank.isStaff()) {
            return RankInfoContext.EMPTY;
        }
        NetworkProfileView profile = networkConfigService.getActiveProfile();
        if (profile == null) {
            return RankInfoContext.EMPTY;
        }
        return profile.getRankInfo(rank.name())
                .map(this::toContext)
                .orElse(RankInfoContext.EMPTY);
    }

    private RankInfoContext toContext(RankInfoView infoView) {
        Component tooltip = buildTooltip(infoView);
        return new RankInfoContext(tooltip, normalizeUrl(infoView.infoUrl()));
    }

    private Component buildTooltip(RankInfoView infoView) {
        if (infoView.tooltipLines().isEmpty()) {
            return null;
        }
        Component combined = Component.empty();
        for (int i = 0; i < infoView.tooltipLines().size(); i++) {
            String rawLine = infoView.tooltipLines().get(i);
            Component lineComponent = legacySerializer.deserialize(applyPlaceholders(rawLine, infoView));
            combined = combined.append(lineComponent);
            if (i + 1 < infoView.tooltipLines().size()) {
                combined = combined.append(Component.newline());
            }
        }
        return combined;
    }

    private String applyPlaceholders(String template, RankInfoView infoView) {
        if (template == null) {
            return "";
        }
        return template
                .replace("{DISPLAYNAME}", infoView.displayName())
                .replace("{FULLPREFIX}", infoView.fullPrefix());
    }

    private Component applyInteractions(Component base,
                                        Component hoverComponent,
                                        String url) {
        if (base == null) {
            return Component.empty();
        }
        Component output = base;
        if (hoverComponent != null) {
            output = output.hoverEvent(HoverEvent.showText(hoverComponent));
        }
        if (url != null && !url.isBlank()) {
            output = output.clickEvent(ClickEvent.openUrl(url));
        }
        return output;
    }

    private String normalizeUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        return candidate.trim();
    }

    private record RankInfoContext(Component tooltip, String infoUrl) {
        private static final RankInfoContext EMPTY = new RankInfoContext(null, "");
    }
}
