package sh.harold.fulcrum.data.playtime;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper that maintains hierarchical playtime counters inside the core
 * {@code players} Mongo collection. The tracker increments totals for each
 * completed gameplay segment while ensuring idempotency by recording processed
 * segment identifiers on the document itself.
 */
public final class PlaytimeTracker {

    private static final String DEFAULT_COLLECTION = "players";
    private final MongoCollection<Document> players;
    private final Logger logger;

    public PlaytimeTracker(MongoDatabase database, Logger logger) {
        this(database, DEFAULT_COLLECTION, logger);
    }

    public PlaytimeTracker(MongoDatabase database, String collectionName, Logger logger) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(collectionName, "collectionName");
        this.players = database.getCollection(collectionName);
        this.logger = logger != null ? logger : Logger.getLogger(PlaytimeTracker.class.getName());
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalised = text.trim().toLowerCase();
            return "true".equals(normalised) || "yes".equals(normalised) || "1".equals(normalised);
        }
        return false;
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

    private static String buildSegmentKey(PlayerSessionRecord session, PlayerSessionRecord.Segment segment) {
        String sessionId = Optional.ofNullable(session.getSessionId()).orElse("unknown-session");
        String segmentId = segment.getSegmentId();
        if (segmentId == null || segmentId.isBlank()) {
            segmentId = Long.toString(segment.getStartedAt());
        }
        return sessionId + ":" + segmentId;
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    /**
     * Record playtime for a single segment. Segments flagged as queue time or
     * without gameplay metadata are ignored.
     */
    public void recordSegment(PlayerSessionRecord session, PlayerSessionRecord.Segment segment) {
        if (session == null || segment == null) {
            return;
        }
        if (!"MINIGAME".equalsIgnoreCase(nullSafe(segment.getType()))) {
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

        String playerId = session.getPlayerId() != null ? session.getPlayerId().toString() : null;
        if (playerId == null) {
            return;
        }

        String segmentKey = buildSegmentKey(session, segment);
        String familyPath = "playtime.families." + sanitiseKey(family);
        Document inc = new Document("playtime.totalMs", duration)
                .append(familyPath + ".totalMs", duration);
        if (variant != null && !variant.isBlank()) {
            String variantPath = familyPath + ".variants." + sanitiseKey(variant);
            inc.append(variantPath + ".totalMs", duration);
        }

        Document set = new Document("playtime.processedSegments." + segmentKey, endedAt);
        set.append("playtime.families." + sanitiseKey(family) + ".familyId", family);
        if (variant != null && !variant.isBlank()) {
            set.append(familyPath + ".variants." + sanitiseKey(variant) + ".variantId", variant);
        }

        Document update = new Document()
                .append("$inc", inc)
                .append("$set", set);

        UpdateOptions options = new UpdateOptions().upsert(true);

        try {
            players.updateOne(
                    Filters.and(
                            Filters.eq("_id", playerId),
                            Filters.exists("playtime.processedSegments." + segmentKey, false)
                    ),
                    update,
                    options
            );
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to update playtime for " + playerId + " (segment=" + segmentKey + ")", exception);
        }
    }

    /**
     * Iterate over all completed segments on the session record and persist
     * their playtime totals.
     */
    public void recordCompletedSegments(PlayerSessionRecord session) {
        if (session == null) {
            return;
        }
        for (PlayerSessionRecord.Segment segment : session.getSegments()) {
            if (segment != null && segment.getEndedAt() != null) {
                recordSegment(session, segment);
            }
        }
    }

    private long resolvePlayStart(Map<String, Object> metadata, long defaultStart) {
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

    private String resolveFamily(PlayerSessionRecord session, Map<String, Object> metadata) {
        String family = metadata != null ? stringValue(metadata.get("family")) : null;
        if (family != null && !family.isBlank()) {
            return family;
        }
        return activeMinigameValue(session, "family");
    }

    private String resolveVariant(PlayerSessionRecord session, Map<String, Object> metadata) {
        String variant = metadata != null ? stringValue(metadata.get("variant")) : null;
        if (variant != null && !variant.isBlank()) {
            return variant;
        }
        return activeMinigameValue(session, "variant");
    }

    @SuppressWarnings("unchecked")
    private String activeMinigameValue(PlayerSessionRecord session, String key) {
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
}
