package sh.harold.fulcrum.common.settings;

import java.util.Locale;
import java.util.Objects;

/**
 * Supported debug tiers for player-visible diagnostics.
 */
public enum PlayerDebugLevel {
    NONE,
    PLAYER,
    COUNCIL,
    STAFF;

    /**
     * Attempt to coerce a persisted value into a {@link PlayerDebugLevel}, falling back to {@link #NONE}.
     *
     * @param raw stored value (string, number, boolean)
     * @return resolved debug level
     */
    public static PlayerDebugLevel from(Object raw) {
        if (raw == null) {
            return NONE;
        }
        if (raw instanceof PlayerDebugLevel level) {
            return level;
        }
        if (raw instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty()) {
                return NONE;
            }
            try {
                return valueOf(normalized.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return NONE;
            }
        }
        return NONE;
    }

    /**
     * Null-safe setter helper that defaults to {@link #NONE}.
     */
    public static PlayerDebugLevel sanitize(PlayerDebugLevel level) {
        return Objects.requireNonNullElse(level, NONE);
    }

    /**
     * Determine whether this tier should emit debug messaging.
     */
    public boolean isEnabled() {
        return this != NONE;
    }
}
