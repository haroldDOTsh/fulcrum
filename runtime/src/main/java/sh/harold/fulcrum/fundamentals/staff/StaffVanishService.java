package sh.harold.fulcrum.fundamentals.staff;

import sh.harold.fulcrum.fundamentals.actionflag.ActionFlag;
import sh.harold.fulcrum.fundamentals.actionflag.ActionFlagService;
import sh.harold.fulcrum.fundamentals.actionflag.OverrideRequest;
import sh.harold.fulcrum.fundamentals.actionflag.OverrideScopeHandle;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple facade around the action flag service that keeps track of which players
 * were vanished explicitly through the staff tooling.
 */
public final class StaffVanishService {
    private static final OverrideRequest VANISH_REQUEST = OverrideRequest.allow(ActionFlag.INVISIBLE_PACKET);

    private final ActionFlagService flagService;
    private final Map<UUID, OverrideScopeHandle> overrides = new ConcurrentHashMap<>();

    public StaffVanishService(ActionFlagService flagService) {
        this.flagService = Objects.requireNonNull(flagService, "flagService");
    }

    /**
     * Enables vanish for the given player.
     *
     * @return {@code true} if vanish state changed, {@code false} if they were already vanished.
     */
    public boolean enable(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (overrides.containsKey(playerId)) {
            return false;
        }
        OverrideScopeHandle handle = flagService.pushOverride(playerId, VANISH_REQUEST);
        OverrideScopeHandle previous = overrides.putIfAbsent(playerId, handle);
        if (previous != null) {
            flagService.popOverride(handle);
            return false;
        }
        return true;
    }

    /**
     * Disables vanish for the given player.
     *
     * @return {@code true} if vanish was disabled, {@code false} if they were not vanished by staff.
     */
    public boolean disable(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        OverrideScopeHandle handle = overrides.remove(playerId);
        if (handle == null) {
            return false;
        }
        flagService.popOverride(handle);
        return true;
    }

    /**
     * Toggles vanish state for the given player.
     *
     * @return {@code true} if the player is now vanished, {@code false} otherwise.
     */
    public boolean toggle(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (overrides.containsKey(playerId)) {
            disable(playerId);
            return false;
        }
        enable(playerId);
        return true;
    }

    public boolean isVanished(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return overrides.containsKey(playerId);
    }

    public void shutdown() {
        overrides.values().forEach(flagService::popOverride);
        overrides.clear();
    }
}
