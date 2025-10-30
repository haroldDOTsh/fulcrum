package sh.harold.fulcrum.message.debug;

import net.kyori.adventure.audience.Audience;

/**
 * Resolves the effective debug tier for recipients and determines whether a message
 * guarded by a required tier should be delivered.
 */
public interface DebugGate {

    DebugTier tierFor(Audience audience);

    default boolean canView(Audience audience, DebugTier required) {
        if (required == null || required == DebugTier.NONE) {
            return true;
        }
        DebugTier actual = tierFor(audience);
        if (actual == null) {
            actual = DebugTier.NONE;
        }
        return actual.allows(required);
    }
}
