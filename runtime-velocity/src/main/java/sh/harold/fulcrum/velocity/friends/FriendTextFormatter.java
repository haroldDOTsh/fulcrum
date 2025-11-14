package sh.harold.fulcrum.velocity.friends;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;

import java.util.UUID;

/**
 * Utility helpers for formatting friend-facing components consistently.
 */
final class FriendTextFormatter {

    private FriendTextFormatter() {
    }

    static Component formatName(UUID playerId,
                                String fallbackName,
                                RankService rankService,
                                Logger logger) {
        String effectiveName = fallbackName != null ? fallbackName : "Unknown";
        Rank rank = resolveRank(playerId, rankService, logger);
        NamedTextColor nameColor = rank != null && rank.getNameColor() != null
                ? rank.getNameColor()
                : NamedTextColor.AQUA;

        Component nameComponent = Component.text(effectiveName, nameColor);
        if (rank == null || rank == Rank.DEFAULT) {
            return nameComponent;
        }

        String prefixLegacy = firstNonBlank(rank.getFullPrefix(), rank.getShortPrefix());
        if (prefixLegacy == null || prefixLegacy.isBlank()) {
            return nameComponent;
        }

        Component prefixComponent = LegacyComponents.deserialize(prefixLegacy);
        if (prefixComponent == null || prefixComponent.equals(Component.empty())) {
            return nameComponent;
        }

        return Component.text()
                .append(prefixComponent)
                .append(Component.text(" "))
                .append(nameComponent)
                .build();
    }

    static Component aqua(String text) {
        return Component.text(text, NamedTextColor.AQUA);
    }

    static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    static Component red(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    static Component green(String text) {
        return Component.text(text, NamedTextColor.GREEN);
    }

    private static Rank resolveRank(UUID playerId,
                                    RankService rankService,
                                    Logger logger) {
        if (playerId == null || rankService == null) {
            return Rank.DEFAULT;
        }
        try {
            Rank rank = rankService.getEffectiveRankSync(playerId);
            return rank != null ? rank : Rank.DEFAULT;
        } catch (Exception ex) {
            if (logger != null) {
                logger.debug("Failed to resolve rank for {}", playerId, ex);
            }
            return Rank.DEFAULT;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static final class LegacyComponents {
        private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer SERIALIZER =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();

        private LegacyComponents() {
        }

        static Component deserialize(String legacy) {
            if (legacy == null || legacy.isBlank()) {
                return Component.empty();
            }
            return SERIALIZER.deserialize(legacy);
        }
    }
}
