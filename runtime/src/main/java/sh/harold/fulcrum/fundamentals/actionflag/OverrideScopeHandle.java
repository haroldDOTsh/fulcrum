package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.Objects;
import java.util.UUID;

/**
 * Handle returned when an override scope is pushed.
 * Must be supplied when removing the override.
 */
public record OverrideScopeHandle(UUID playerId, int token) {
    public OverrideScopeHandle(UUID playerId, int token) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.token = token;
    }
}
