package sh.harold.fulcrum.common.cooldown;

import java.time.Duration;
import java.util.Objects;

/**
 * Describes how long a cooldown lasts and how repeated acquisitions are handled.
 *
 * @param window duration of the cooldown (must be positive)
 * @param policy policy determining what to do when the cooldown is already active
 */
public record CooldownSpec(Duration window, CooldownPolicy policy) {

    public CooldownSpec {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Cooldown window must be positive");
        }
        policy = Objects.requireNonNullElse(policy, CooldownPolicy.REJECT_WHILE_ACTIVE);
    }

    public static CooldownSpec rejecting(Duration window) {
        return new CooldownSpec(window, CooldownPolicy.REJECT_WHILE_ACTIVE);
    }

    public static CooldownSpec extending(Duration window) {
        return new CooldownSpec(window, CooldownPolicy.EXTEND_ON_ACQUIRE);
    }
}
