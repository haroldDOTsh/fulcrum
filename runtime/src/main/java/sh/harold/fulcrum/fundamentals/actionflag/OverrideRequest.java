package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.GameMode;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Request describing an override scope (force certain flags on/off).
 */
public final class OverrideRequest {
    private final long enableMask;
    private final long disableMask;
    private final GameMode gamemode;

    private OverrideRequest(long enableMask, long disableMask, GameMode gamemode) {
        this.enableMask = enableMask;
        this.disableMask = disableMask;
        this.gamemode = gamemode;
    }

    public static OverrideRequest allow(ActionFlag... flags) {
        return new OverrideRequest(maskOf(flags), 0L, null);
    }

    public static OverrideRequest deny(ActionFlag... flags) {
        return new OverrideRequest(0L, maskOf(flags), null);
    }

    public static OverrideRequest of(Set<ActionFlag> toEnable, Set<ActionFlag> toDisable) {
        Objects.requireNonNull(toEnable, "toEnable");
        Objects.requireNonNull(toDisable, "toDisable");
        return new OverrideRequest(maskOf(toEnable), maskOf(toDisable), null);
    }

    public OverrideRequest withGamemode(GameMode gamemode) {
        return new OverrideRequest(enableMask, disableMask, gamemode);
    }

    private static long maskOf(Set<ActionFlag> flags) {
        long mask = 0L;
        for (ActionFlag flag : flags) {
            mask |= flag.mask();
        }
        return mask;
    }

    private static long maskOf(ActionFlag[] flags) {
        Objects.requireNonNull(flags, "flags");
        long mask = 0L;
        for (ActionFlag flag : flags) {
            if (flag != null) {
                mask |= flag.mask();
            }
        }
        return mask;
    }

    public OverrideRequest combine(OverrideRequest other) {
        Objects.requireNonNull(other, "other");
        GameMode combinedGamemode = other.gamemode != null ? other.gamemode : this.gamemode;
        return new OverrideRequest(
                this.enableMask | other.enableMask,
                this.disableMask | other.disableMask,
                combinedGamemode
        );
    }

    public long enableMask() {
        return enableMask;
    }

    public long disableMask() {
        return disableMask;
    }

    public Optional<GameMode> gamemode() {
        return Optional.ofNullable(gamemode);
    }
}
