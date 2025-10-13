package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Central service managing player action flags and context bundles.
 */
public final class ActionFlagService {
    private final Logger logger;
    private final Map<String, FlagBundle> bundles = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerFlagProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicInteger overrideSequence = new AtomicInteger();

    public ActionFlagService(Logger logger) {
        this.logger = logger;
    }

    public FlagContextHandle registerContext(String id, FlagBundle bundle) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bundle, "bundle");
        bundles.put(id, bundle);
        return new FlagContextHandle(this, bundle);
    }

    public Optional<FlagContextHandle> getContextHandle(String id) {
        FlagBundle bundle = bundles.get(id);
        if (bundle == null) {
            return Optional.empty();
        }
        return Optional.of(new FlagContextHandle(this, bundle));
    }

    public boolean hasContext(String id) {
        return bundles.containsKey(id);
    }

    public void applyContext(UUID playerId, String contextId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(contextId, "contextId");
        FlagBundle bundle = bundles.get(contextId);
        if (bundle == null) {
            logger.warning("Attempted to apply unknown flag context '" + contextId + "' to player " + playerId);
            return;
        }
        PlayerFlagProfile profile = profiles.computeIfAbsent(playerId, ignored -> new PlayerFlagProfile());
        profile.setBaseContext(bundle.id(), bundle.mask());
    }

    public void applyContext(Collection<UUID> playerIds, String contextId) {
        if (playerIds == null || playerIds.isEmpty()) {
            return;
        }
        for (UUID playerId : playerIds) {
            applyContext(playerId, contextId);
        }
    }

    public OverrideScopeHandle pushOverride(UUID playerId, OverrideRequest request) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(request, "request");
        PlayerFlagProfile profile = profiles.computeIfAbsent(playerId, ignored -> new PlayerFlagProfile());
        int token = overrideSequence.incrementAndGet();
        profile.addOverride(new PlayerFlagProfile.OverrideEntry(token, request.enableMask(), request.disableMask()));
        return new OverrideScopeHandle(playerId, token);
    }

    public void popOverride(OverrideScopeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        PlayerFlagProfile profile = profiles.get(handle.playerId());
        if (profile == null) {
            return;
        }
        profile.removeOverride(handle.token());
    }

    public void clearOverrides(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PlayerFlagProfile profile = profiles.get(playerId);
        if (profile != null) {
            profile.clearOverrides();
        }
    }

    public void clear(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        profiles.remove(playerId);
    }

    public boolean allows(UUID playerId, ActionFlag flag) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(flag, "flag");
        PlayerFlagProfile profile = profiles.get(playerId);
        if (profile == null) {
            return false;
        }
        return profile.allows(flag);
    }

    public PlayerFlagSnapshot snapshot(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PlayerFlagProfile profile = profiles.get(playerId);
        if (profile == null) {
            return new PlayerFlagSnapshot("", ActionFlagMaskUtil.flagsFromMask(0L), java.util.List.of());
        }
        return profile.snapshot();
    }
}
