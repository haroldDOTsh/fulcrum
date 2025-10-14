package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.GameMode;

import java.util.*;

/**
 * Immutable view of a player's flag state for debugging or monitoring.
 */
public final class PlayerFlagSnapshot {
    private final String baseContextId;
    private final Set<ActionFlag> activeFlags;
    private final List<OverrideSnapshot> overrides;
    private final Optional<GameMode> gamemode;

    PlayerFlagSnapshot(String baseContextId,
                       Set<ActionFlag> activeFlags,
                       List<OverrideSnapshot> overrides,
                       Optional<GameMode> gamemode) {
        this.baseContextId = baseContextId;
        this.activeFlags = Collections.unmodifiableSet(activeFlags);
        this.overrides = List.copyOf(overrides);
        this.gamemode = Objects.requireNonNull(gamemode, "gamemode");
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

    public Optional<GameMode> gamemode() {
        return gamemode;
    }

    public record OverrideSnapshot(int token,
                                   Set<ActionFlag> enabled,
                                   Set<ActionFlag> disabled,
                                   Optional<GameMode> gamemode) {
    }
}
