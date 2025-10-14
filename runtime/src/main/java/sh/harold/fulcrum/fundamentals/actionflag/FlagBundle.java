package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.GameMode;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable definition of a context default.
 * Specifies which action flags should be enabled when the bundle is applied.
 */
public final class FlagBundle {
    private final String id;
    private final long allowMask;
    private final GameMode gameMode;

    private FlagBundle(String id, long allowMask, GameMode gameMode) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Bundle id cannot be blank");
        }
        this.allowMask = allowMask;
        this.gameMode = gameMode;
    }

    public static FlagBundle of(String id, Set<ActionFlag> flags) {
        Objects.requireNonNull(flags, "flags");
        long mask = 0L;
        for (ActionFlag flag : flags) {
            mask |= flag.mask();
        }
        return new FlagBundle(id, mask, null);
    }

    public static FlagBundle of(String id, ActionFlag... flags) {
        Objects.requireNonNull(flags, "flags");
        long mask = 0L;
        for (ActionFlag flag : flags) {
            if (flag != null) {
                mask |= flag.mask();
            }
        }
        return new FlagBundle(id, mask, null);
    }

    public FlagBundle withGamemode(GameMode gamemode) {
        return new FlagBundle(id, allowMask, gamemode);
    }

    public String id() {
        return id;
    }

    public long mask() {
        return allowMask;
    }

    public boolean allows(ActionFlag flag) {
        return (allowMask & flag.mask()) != 0L;
    }

    public Optional<GameMode> gamemode() {
        return Optional.ofNullable(gameMode);
    }
}
