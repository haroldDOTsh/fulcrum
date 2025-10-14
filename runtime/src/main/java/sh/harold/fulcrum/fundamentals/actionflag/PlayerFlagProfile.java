package sh.harold.fulcrum.fundamentals.actionflag;

import org.bukkit.GameMode;

import java.util.*;

final class PlayerFlagProfile {
    private final Deque<OverrideEntry> overrides = new ArrayDeque<>();
    private String baseContextId;
    private long baseMask;
    private GameMode baseGamemode;
    private long effectiveMask;
    private GameMode effectiveGamemode;

    PlayerFlagProfile() {
        this.baseContextId = "";
        this.baseMask = 0L;
        this.baseGamemode = null;
        this.effectiveMask = 0L;
        this.effectiveGamemode = null;
    }

    synchronized void setBaseContext(String contextId, long mask, GameMode gamemode) {
        this.baseContextId = contextId != null ? contextId : "";
        this.baseMask = mask;
        this.baseGamemode = gamemode;
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

    synchronized Optional<GameMode> effectiveGamemode() {
        return Optional.ofNullable(effectiveGamemode);
    }

    synchronized boolean allows(ActionFlag flag) {
        return (effectiveMask & flag.mask()) != 0L;
    }

    synchronized PlayerFlagState state() {
        return new PlayerFlagState(effectiveMask, effectiveGamemode);
    }

    synchronized PlayerFlagSnapshot snapshot() {
        Set<ActionFlag> activeFlags = ActionFlagMaskUtil.flagsFromMask(effectiveMask);
        List<PlayerFlagSnapshot.OverrideSnapshot> overrideSnapshots = new ArrayList<>(overrides.size());
        for (OverrideEntry entry : overrides) {
            overrideSnapshots.add(new PlayerFlagSnapshot.OverrideSnapshot(
                    entry.token,
                    ActionFlagMaskUtil.flagsFromMask(entry.enableMask),
                    ActionFlagMaskUtil.flagsFromMask(entry.disableMask),
                    Optional.ofNullable(entry.gamemode)
            ));
        }
        return new PlayerFlagSnapshot(
                baseContextId,
                activeFlags,
                overrideSnapshots,
                Optional.ofNullable(effectiveGamemode)
        );
    }

    private void recompute() {
        long mask = baseMask;
        GameMode gamemode = baseGamemode;
        for (OverrideEntry override : overrides) {
            mask |= override.enableMask;
            mask &= ~override.disableMask;
            if (override.gamemode != null) {
                gamemode = override.gamemode;
            }
        }
        this.effectiveMask = mask;
        this.effectiveGamemode = gamemode;
    }

    record OverrideEntry(int token, long enableMask, long disableMask, GameMode gamemode) {
    }
}
