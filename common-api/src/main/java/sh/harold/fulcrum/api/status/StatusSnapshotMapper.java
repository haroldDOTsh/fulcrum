package sh.harold.fulcrum.api.status;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight mapper for encoding and decoding status snapshots.
 */
public final class StatusSnapshotMapper {

    private StatusSnapshotMapper() {
    }

    private static final String PRESENCE_KEY = "presence";
    private static final String BADGE_KEY = "activityBadge";
    private static final String UPDATED_AT_KEY = "updatedAtEpochMillis";

    public static Map<String, Object> toMap(PlayerStatus status) {
        Objects.requireNonNull(status, "status");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PRESENCE_KEY, status.presence().name());
        if (status.activityBadge() != null && !status.activityBadge().isBlank()) {
            payload.put(BADGE_KEY, status.activityBadge());
        }
        payload.put(UPDATED_AT_KEY, status.updatedAtEpochMillis());
        return payload;
    }

    public static PlayerStatus fromObject(UUID playerId, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        PresenceStatus presence = parsePresence(map.get(PRESENCE_KEY));
        String badge = sanitizeBadge(map.get(BADGE_KEY));
        long updatedAt = parseLong(map.get(UPDATED_AT_KEY));
        if (presence == null) {
            presence = PresenceStatus.OFFLINE;
        }
        return new PlayerStatus(playerId, presence, badge, updatedAt);
    }

    private static PresenceStatus parsePresence(Object value) {
        if (value instanceof PresenceStatus presenceStatus) {
            return presenceStatus;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toUpperCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                try {
                    return PresenceStatus.valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String sanitizeBadge(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static long parseLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0L, Long.parseLong(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}

