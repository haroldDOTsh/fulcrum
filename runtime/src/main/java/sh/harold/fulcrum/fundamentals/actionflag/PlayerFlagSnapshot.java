package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable view of a player's flag state for debugging or monitoring.
 */
public final class PlayerFlagSnapshot {
    private final String baseContextId;
    private final Set<ActionFlag> activeFlags;
    private final List<OverrideSnapshot> overrides;

    PlayerFlagSnapshot(String baseContextId,
                       Set<ActionFlag> activeFlags,
                       List<OverrideSnapshot> overrides) {
        this.baseContextId = baseContextId;
        this.activeFlags = Collections.unmodifiableSet(activeFlags);
        this.overrides = Collections.unmodifiableList(overrides);
    }

    public String baseContextId() {
        return baseContextId;
    }

    public Set<ActionFlag> activeFlags() {
        return activeFlags;
    }

    public List<OverrideSnapshot> overrides() {
        return overrides;
    }

    public record OverrideSnapshot(int token, Set<ActionFlag> enabled, Set<ActionFlag> disabled) {
    }
}
