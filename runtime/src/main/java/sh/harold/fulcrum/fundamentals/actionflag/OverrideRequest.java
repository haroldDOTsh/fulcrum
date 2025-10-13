package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.Objects;
import java.util.Set;

/**
 * Request describing an override scope (force certain flags on/off).
 */
public final class OverrideRequest {
    private final long enableMask;
    private final long disableMask;

    private OverrideRequest(long enableMask, long disableMask) {
        this.enableMask = enableMask;
        this.disableMask = disableMask;
    }

    public static OverrideRequest allow(ActionFlag... flags) {
        return new OverrideRequest(maskOf(flags), 0L);
    }

    public static OverrideRequest deny(ActionFlag... flags) {
        return new OverrideRequest(0L, maskOf(flags));
    }

    public static OverrideRequest of(Set<ActionFlag> toEnable, Set<ActionFlag> toDisable) {
        Objects.requireNonNull(toEnable, "toEnable");
        Objects.requireNonNull(toDisable, "toDisable");
        return new OverrideRequest(maskOf(toEnable), maskOf(toDisable));
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
        return new OverrideRequest(
                this.enableMask | other.enableMask,
                this.disableMask | other.disableMask
        );
    }

    public long enableMask() {
        return enableMask;
    }

    public long disableMask() {
        return disableMask;
    }
}
