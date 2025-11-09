package sh.harold.fulcrum.data.playtime;

import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Updates playtime counters on the session record so they can be flushed to Mongo at logout.
 */
public final class PlaytimeTracker {

    private final Logger logger;

    public PlaytimeTracker(Logger logger) {
        this.logger = logger != null ? logger : Logger.getLogger(PlaytimeTracker.class.getName());
    }

    public PlaytimeTracker() {
        this(null);
    }

    private static Map<String, Object> ensureMap(Map<String, Object> root, String key) {
        Object existing = root.get(key);
        if (existing instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> created = new java.util.LinkedHashMap<>();
        root.put(key, created);
        return created;
    }

    private static void increment(Map<String, Object> map, String key, long delta) {
        long current = 0L;
        Object value = map.get(key);
        if (value instanceof Number number) {
            current = number.longValue();
        }
        map.put(key, current + delta);
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalised = text.trim().toLowerCase(Locale.ROOT);
            return Objects.equals(normalised, "true") || Objects.equals(normalised, "yes") || Objects.equals(normalised, "1");
        }
        return false;
    }

    private static String buildSegmentKey(PlayerSessionRecord session, PlayerSessionRecord.Segment segment) {
        String sessionId = Optional.ofNullable(session.getSessionId()).orElse("unknown-session");
        String segmentId = segment.getSegmentId();
        if (segmentId == null || segmentId.isBlank()) {
            segmentId = Long.toString(segment.getStartedAt());
        }
        return sessionId + ":" + segmentId;
    }

    private static long resolvePlayStart(Map<String, Object> metadata, long defaultStart) {
        if (metadata == null) {
            return defaultStart;
        }
        Object value = metadata.get("playStartedAt");
        if (value instanceof Number number) {
            return Math.max(number.longValue(), defaultStart);
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text.trim());
                return Math.max(parsed, defaultStart);
            } catch (NumberFormatException ignored) {
                return defaultStart;
            }
        }
        return defaultStart;
    }

    private static String resolveFamily(PlayerSessionRecord session, Map<String, Object> metadata) {
        String family = metadata != null ? stringValue(metadata.get("family")) : null;
        if (family != null && !family.isBlank()) {
            return family;
        }
        return activeMinigameValue(session, "family");
    }

    private static String resolveVariant(PlayerSessionRecord session, Map<String, Object> metadata) {
        String variant = metadata != null ? stringValue(metadata.get("variant")) : null;
        if (variant != null && !variant.isBlank()) {
            return variant;
        }
        return activeMinigameValue(session, "variant");
    }

    @SuppressWarnings("unchecked")
    private static String activeMinigameValue(PlayerSessionRecord session, String key) {
        if (session == null || session.getMinigames() == null) {
            return null;
        }
        Object active = session.getMinigames().get("active");
        if (active instanceof Map<?, ?> activeMap) {
            Object value = ((Map<String, Object>) activeMap).get(key);
            return stringValue(value);
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text != null && !text.isBlank() ? text : null;
    }

    private static String sanitiseKey(String key) {
        return key.replace('.', '_').replace('$', '_');
    }

    public void recordSegment(PlayerSessionRecord session, PlayerSessionRecord.Segment segment) {
        if (session == null || segment == null) {
            return;
        }
        if (segment.getEndedAt() == null) {
            return;
        }

        Map<String, Object> metadata = segment.getMetadata();
        if (metadata != null && isTrue(metadata.get("queue"))) {
            return;
        }

        long startedAt = segment.getStartedAt();
        long endedAt = segment.getEndedAt();
        long playStart = resolvePlayStart(metadata, startedAt);

        if (playStart <= 0L || endedAt <= playStart) {
            return;
        }

        String family = resolveFamily(session, metadata);
        if (family == null || family.isBlank()) {
            return;
        }
        String variant = resolveVariant(session, metadata);
        long duration = endedAt - playStart;
        if (duration <= 0L) {
            return;
        }

        Map<String, Object> playtime = session.mutablePlaytime();
        Map<String, Object> processed = ensureMap(playtime, "processedSegments");
        String segmentKey = buildSegmentKey(session, segment);
        if (processed.containsKey(segmentKey)) {
            return;
        }
        processed.put(segmentKey, endedAt);

        increment(playtime, "totalMs", duration);

        Map<String, Object> families = ensureMap(playtime, "families");
        String familyKey = sanitiseKey(family);
        Map<String, Object> familyEntry = ensureMap(families, familyKey);
        familyEntry.put("familyId", family);
        increment(familyEntry, "totalMs", duration);

        if (variant != null && !variant.isBlank()) {
            Map<String, Object> variants = ensureMap(familyEntry, "variants");
            String variantKey = sanitiseKey(variant);
            Map<String, Object> variantEntry = ensureMap(variants, variantKey);
            variantEntry.put("variantId", variant);
            increment(variantEntry, "totalMs", duration);
        }
    }

    public void recordCompletedSegments(PlayerSessionRecord session) {
        if (session == null) {
            return;
        }
        for (PlayerSessionRecord.Segment segment : session.getSegments()) {
            if (segment != null && segment.getEndedAt() != null) {
                try {
                    recordSegment(session, segment);
                } catch (Exception exception) {
                    logger.warning(() -> "Failed to record playtime segment: " + exception.getMessage());
                }
            }
        }
    }
}
