package sh.harold.fulcrum.velocity.party;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;

import java.util.UUID;

final class PartyTextFormatter {

    private PartyTextFormatter() {
    }

    static Component formatName(UUID playerId,
                                String fallbackName,
                                RankService rankService,
                                Logger logger) {
        String effectiveName = fallbackName != null ? fallbackName : "Unknown";
        Rank rank = resolveRank(playerId, rankService, logger);
        NamedTextColor nameColor = rank != null && rank.getNameColor() != null
                ? rank.getNameColor()
                : NamedTextColor.YELLOW;

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

    static Component yellow(String text) {
        return Component.text(text, NamedTextColor.YELLOW);
    }

    static Component redNumber(long value) {
        return Component.text(String.valueOf(value), NamedTextColor.RED);
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

    /**
     * Minimal legacy serializer wrapper used to translate legacy prefixes into Components.
     */
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
