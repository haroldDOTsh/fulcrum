package sh.harold.fulcrum.registry.route.util;

import java.util.Locale;

/**
 * Utility helpers for normalising slot identifiers.
 */
public final class SlotIdUtils {

    private SlotIdUtils() {
    }

    public static String sanitize(String slotId) {
        if (slotId == null) {
            return null;
        }
        String trimmed = slotId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String normalize(String slotId) {
        String sanitized = sanitize(slotId);
        return sanitized != null ? sanitized.toLowerCase(Locale.ROOT) : null;
    }
}
