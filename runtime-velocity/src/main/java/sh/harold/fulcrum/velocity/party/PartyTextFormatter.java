package sh.harold.fulcrum.velocity.party;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.session.PlayerSessionRecord;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.Locale;
import java.util.UUID;

final class PartyTextFormatter {

    private PartyTextFormatter() {
    }

    static Component formatName(UUID playerId,
                                String fallbackName,
                                DataAPI dataAPI,
                                VelocityPlayerSessionService sessionService,
                                Logger logger) {
        String effectiveName = fallbackName != null ? fallbackName : "Unknown";
        Rank rank = resolveRank(playerId, dataAPI, sessionService, logger);
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
                                    DataAPI dataAPI,
                                    VelocityPlayerSessionService sessionService,
                                    Logger logger) {
        if (playerId == null) {
            return Rank.DEFAULT;
        }

        String rawRank = null;

        if (sessionService != null) {
            rawRank = sessionService.getSession(playerId)
                    .map(PartyTextFormatter::extractPrimaryRank)
                    .orElse(null);
        }

        if ((rawRank == null || rawRank.isBlank()) && dataAPI != null) {
            try {
                Document doc = dataAPI.collection("players").document(playerId.toString());
                if (doc.exists()) {
                    rawRank = extractPrimaryRank(doc);
                }
            } catch (Exception ex) {
                if (logger != null) {
                    logger.debug("Failed to resolve rank for {}", playerId, ex);
                }
            }
        }

        if (rawRank == null || rawRank.isBlank()) {
            return Rank.DEFAULT;
        }

        String normalized = normalizeRankId(rawRank);
        try {
            return Rank.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            if (logger != null) {
                logger.debug("Unknown rank '{}' for {}, defaulting to DEFAULT", rawRank, playerId);
            }
            return Rank.DEFAULT;
        }
    }

    private static String extractPrimaryRank(PlayerSessionRecord record) {
        if (record == null) {
            return null;
        }
        Object primary = record.getRank().get("primary");
        if (primary instanceof String value && !value.isBlank()) {
            return value;
        }
        Object legacy = record.getCore().get("rank");
        return legacy != null ? legacy.toString() : null;
    }

    private static String extractPrimaryRank(Document document) {
        if (document == null) {
            return null;
        }
        String primary = document.get("rankInfo.primary", null);
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return document.get("rank", null);
    }

    private static String normalizeRankId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim()
                .replace(' ', '_')
                .replace('-', '_');
        normalized = normalized.replace("++", "_PLUS_PLUS");
        normalized = normalized.replace("+", "_PLUS");
        return normalized.toUpperCase(Locale.ROOT);
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
