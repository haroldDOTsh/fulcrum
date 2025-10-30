package sh.harold.fulcrum.api.rank;

import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared helpers for resolving rank information from stored player state.
 */
public final class RankStateResolver {
    private RankStateResolver() {
    }

    /**
     * Resolves the effective rank from a {@link PlayerSessionRecord}.
     *
     * @param record player session snapshot
     * @return rank if present; empty when unknown
     */
    public static Optional<Rank> resolve(PlayerSessionRecord record) {
        return resolve(primaryRankId(record));
    }

    /**
     * Resolves the effective rank from a {@link Document} fetched via DataAPI.
     *
     * @param document player profile document
     * @return rank if present; empty when unknown
     */
    public static Optional<Rank> resolve(Document document) {
        return resolve(primaryRankId(document));
    }

    /**
     * Normalises and parses a raw rank identifier.
     *
     * @param rawRank rank identifier (e.g. "helper", "donator-1")
     * @return rank if recognised; empty otherwise
     */
    public static Optional<Rank> resolve(String rawRank) {
        if (rawRank == null || rawRank.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeRankId(rawRank);
        try {
            return Optional.of(Rank.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Extracts the primary rank identifier from a session record.
     */
    public static Optional<String> primaryRankId(PlayerSessionRecord record) {
        if (record == null) {
            return Optional.empty();
        }
        Map<String, Object> rank = record.getRank();
        if (rank != null) {
            Object primary = rank.get("primary");
            if (primary instanceof String primaryName && !primaryName.isBlank()) {
                return Optional.of(primaryName);
            }
        }
        Map<String, Object> core = record.getCore();
        if (core != null) {
            Object legacy = core.get("rank");
            if (legacy instanceof String legacyRank && !legacyRank.isBlank()) {
                return Optional.of(legacyRank);
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the primary rank identifier from a DataAPI document.
     */
    public static Optional<String> primaryRankId(Document document) {
        if (document == null) {
            return Optional.empty();
        }
        String primary = document.get("rankInfo.primary", null);
        if (primary != null && !primary.isBlank()) {
            return Optional.of(primary);
        }
        String legacy = document.get("rank", null);
        if (legacy != null && !legacy.isBlank()) {
            return Optional.of(legacy);
        }
        return Optional.empty();
    }

    /**
     * Normalises a rank identifier into the enum constant naming format.
     */
    public static String normalizeRankId(String value) {
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

    private static Optional<Rank> resolve(Optional<String> raw) {
        return raw.flatMap(RankStateResolver::resolve);
    }
}
