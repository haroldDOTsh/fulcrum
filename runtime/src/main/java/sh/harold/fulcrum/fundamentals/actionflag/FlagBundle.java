package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable definition of a context default.
 * Specifies which action flags should be enabled when the bundle is applied.
 */
public final class FlagBundle {
    private final String id;
    private final long allowMask;

    private FlagBundle(String id, long allowMask) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Bundle id cannot be blank");
        }
        this.allowMask = allowMask;
    }

    public static FlagBundle of(String id, Set<ActionFlag> flags) {
        Objects.requireNonNull(flags, "flags");
        long mask = 0L;
        for (ActionFlag flag : flags) {
            mask |= flag.mask();
        }
        return new FlagBundle(id, mask);
    }

    public static FlagBundle of(String id, ActionFlag... flags) {
        Objects.requireNonNull(flags, "flags");
        long mask = 0L;
        for (ActionFlag flag : flags) {
            if (flag != null) {
                mask |= flag.mask();
            }
        }
        return new FlagBundle(id, mask);
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
}
