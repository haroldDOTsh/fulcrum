package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.*;

final class PlayerFlagProfile {
    private final Deque<OverrideEntry> overrides = new ArrayDeque<>();
    private String baseContextId;
    private long baseMask;
    private long effectiveMask;

    PlayerFlagProfile() {
        this.baseContextId = "";
        this.baseMask = 0L;
        this.effectiveMask = 0L;
    }

    synchronized void setBaseContext(String contextId, long mask) {
        this.baseContextId = contextId != null ? contextId : "";
        this.baseMask = mask;
        recompute();
    }

    synchronized void addOverride(OverrideEntry override) {
        overrides.addLast(override);
        recompute();
    }

    synchronized void removeOverride(int token) {
        overrides.removeIf(entry -> entry.token == token);
        recompute();
    }

    synchronized void clearOverrides() {
        overrides.clear();
        recompute();
    }

    synchronized long effectiveMask() {
        return effectiveMask;
    }

    synchronized boolean allows(ActionFlag flag) {
        return (effectiveMask & flag.mask()) != 0L;
    }

    synchronized PlayerFlagSnapshot snapshot() {
        Set<ActionFlag> activeFlags = ActionFlagMaskUtil.flagsFromMask(effectiveMask);
        List<PlayerFlagSnapshot.OverrideSnapshot> overrideSnapshots = new ArrayList<>(overrides.size());
        for (OverrideEntry entry : overrides) {
            overrideSnapshots.add(new PlayerFlagSnapshot.OverrideSnapshot(
                    entry.token,
                    ActionFlagMaskUtil.flagsFromMask(entry.enableMask),
                    ActionFlagMaskUtil.flagsFromMask(entry.disableMask)
            ));
        }
        return new PlayerFlagSnapshot(baseContextId, activeFlags, overrideSnapshots);
    }

    private void recompute() {
        long mask = baseMask;
        for (OverrideEntry override : overrides) {
            mask |= override.enableMask;
            mask &= ~override.disableMask;
        }
        this.effectiveMask = mask;
    }

    record OverrideEntry(int token, long enableMask, long disableMask) {
    }
}
