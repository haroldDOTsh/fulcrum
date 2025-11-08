package sh.harold.fulcrum.api.chat.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.ChatFormatter;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.network.RankInfoView;
import sh.harold.fulcrum.api.network.RankVisualView;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.common.cache.PlayerCache;

import java.util.*;

/**
 * Default Hypixel-style formatter.
 * Format: [RANK] Username: message
 * Supports &amp; color codes through Adventure API's LegacyComponentSerializer
 */
public class DefaultChatFormatter implements ChatFormatter {
    private final RankService rankService;
    private final NetworkConfigService networkConfigService;
    private final PlayerCache playerCache;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public DefaultChatFormatter(RankService rankService,
                                NetworkConfigService networkConfigService,
                                PlayerCache playerCache) {
        this.rankService = rankService;
        this.networkConfigService = networkConfigService;
        this.playerCache = playerCache;
    }

    @Override
    public Component format(Player player, Component message) {
        UUID playerId = player.getUniqueId();
        Rank rank = rankService.getEffectiveRankSync(playerId);
        if (rank == null) {
            rank = Rank.DEFAULT;
        }

        ChatCosmeticOverrides cosmeticOverrides = resolveChatCosmetics(playerId, rank);

        Component formatted = Component.empty();

        if (cosmeticOverrides.chatColorOverride() != null) {
            message = message.colorIfAbsent(cosmeticOverrides.chatColorOverride());
        }

        RankInfoContext rankInfoContext = resolveRankInfo(rank);
        Component tooltipComponent = rankInfoContext.tooltip();
        String infoUrl = rankInfoContext.infoUrl();

        String prefixSource = Optional.ofNullable(cosmeticOverrides.rankVisualOverride())
                .map(RankVisualView::fullPrefix)
                .filter(value -> !value.isBlank())
                .orElse(rank.getFullPrefix());

        if (prefixSource != null && !prefixSource.isBlank()) {
            Component rankComponent = legacySerializer.deserialize(prefixSource);
            rankComponent = applyInteractions(rankComponent, tooltipComponent, infoUrl);
            formatted = formatted.append(rankComponent).append(Component.space());
        }

        TextColor nameColor = cosmeticOverrides.nameColorOverride() != null
                ? cosmeticOverrides.nameColorOverride()
                : rank.getTextColor();

        Component nameComponent = Component.text(player.getName())
                .color(nameColor);
        nameComponent = applyInteractions(nameComponent, tooltipComponent, infoUrl);
        formatted = formatted.append(nameComponent);

        formatted = formatted.append(Component.text(": ", NamedTextColor.GRAY));
        formatted = formatted.append(message);

        return formatted;
    }

    private ChatCosmeticOverrides resolveChatCosmetics(UUID playerId, Rank fallbackRank) {
        if (playerCache == null || playerId == null) {
            return ChatCosmeticOverrides.EMPTY;
        }
        PlayerCache.CachedDocument document = playerCache.cosmetics(playerId);
        @SuppressWarnings("unchecked")
        Map<String, Object> chatSection = document.get("chat", Map.class).orElse(Map.of());
        if (chatSection.isEmpty()) {
            return ChatCosmeticOverrides.EMPTY;
        }

        RankVisualView rankVisualOverride = buildVisualOverride(chatSection.get("rankVisualOverride"), fallbackRank);
        TextColor nameColorOverride = parseTextColor(chatSection.get("nameColorOverride"));
        TextColor chatColorOverride = parseTextColor(chatSection.get("chatColorOverride"));

        if (rankVisualOverride == null && nameColorOverride == null && chatColorOverride == null) {
            return ChatCosmeticOverrides.EMPTY;
        }
        return new ChatCosmeticOverrides(rankVisualOverride, nameColorOverride, chatColorOverride);
    }

    private RankVisualView buildVisualOverride(Object raw, Rank fallbackRank) {
        Map<String, Object> values = asMap(raw);
        if (values.isEmpty()) {
            return null;
        }

        String displayName = sanitize(values.get("displayName"));
        String colorCode = sanitize(values.get("colorCode"));
        String fullPrefix = sanitize(values.get("fullPrefix"));
        String shortPrefix = sanitize(values.get("shortPrefix"));
        String nameColor = sanitize(values.get("nameColor"));

        boolean hasOverride = !displayName.isEmpty()
                || !colorCode.isEmpty()
                || !fullPrefix.isEmpty()
                || !shortPrefix.isEmpty()
                || !nameColor.isEmpty();
        if (!hasOverride) {
            return null;
        }

        String resolvedDisplayName = !displayName.isEmpty() ? displayName : fallbackRank.getDisplayName();
        String resolvedColorCode = !colorCode.isEmpty() ? colorCode : fallbackRank.getColorCode();
        String resolvedFullPrefix = !fullPrefix.isEmpty() ? fullPrefix : fallbackRank.getFullPrefix();
        String resolvedShortPrefix = !shortPrefix.isEmpty() ? shortPrefix : fallbackRank.getShortPrefix();
        String resolvedNameColor = !nameColor.isEmpty()
                ? nameColor
                : fallbackRank.getTextColor().asHexString();

        return new RankVisualView(
                resolvedDisplayName,
                resolvedColorCode,
                resolvedFullPrefix,
                resolvedShortPrefix,
                resolvedNameColor
        );
    }

    private TextColor parseTextColor(Object raw) {
        if (!(raw instanceof String token)) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.startsWith("&")) {
            Component colored = legacySerializer.deserialize(trimmed + "x");
            if (colored.color() != null) {
                return colored.color();
            }
            trimmed = trimmed.substring(1);
        }

        if (trimmed.startsWith("&#") && trimmed.length() == 8) {
            trimmed = "#" + trimmed.substring(2);
        }

        if (trimmed.length() == 6 && trimmed.chars().allMatch(Character::isLetterOrDigit)) {
            trimmed = "#" + trimmed;
        }

        NamedTextColor named = NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
        if (named != null) {
            return named;
        }

        try {
            return TextColor.fromHexString(trimmed);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private String sanitize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                copy.put(key.toString(), value);
            }
        });
        return copy;
    }

    private record RankInfoContext(Component tooltip, String infoUrl) {
        private static final RankInfoContext EMPTY = new RankInfoContext(null, "");
    }

    private record ChatCosmeticOverrides(
            RankVisualView rankVisualOverride,
            TextColor nameColorOverride,
            TextColor chatColorOverride
    ) {
        private static final ChatCosmeticOverrides EMPTY = new ChatCosmeticOverrides(null, null, null);
    }
}
