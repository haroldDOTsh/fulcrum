package sh.harold.fulcrum.message.debug;

/**
 * Convenience factories for common debug gate behaviour.
 */
public final class DebugGates {
    private static final DebugGate NONE = audience -> DebugTier.NONE;
    private static final DebugGate STAFF_ONLY = audience -> DebugTier.STAFF;

    private DebugGates() {
    }

    public static DebugGate none() {
        return NONE;
    }

    public static DebugGate fixed(DebugTier tier) {
        return audience -> tier == null ? DebugTier.NONE : tier;
    }

    public static DebugGate staffOnly() {
        return STAFF_ONLY;
    }
}
