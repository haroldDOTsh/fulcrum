package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Central service managing player action flags and context bundles.
 */
public final class ActionFlagService {
    private static final PlayerFlagState EMPTY_STATE = new PlayerFlagState(0L, null);

    private final Logger logger;
    private final Map<String, FlagBundle> bundles = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerFlagProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicInteger overrideSequence = new AtomicInteger();
    private final List<PlayerFlagStateListener> stateListeners = new CopyOnWriteArrayList<>();

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

    public Optional<FlagBundle> getBundle(String id) {
        return Optional.ofNullable(bundles.get(id));
    }

    public boolean hasContext(String id) {
        return bundles.containsKey(id);
    }

    public void addStateListener(PlayerFlagStateListener listener) {
        Objects.requireNonNull(listener, "listener");
        stateListeners.add(listener);
    }

    public void removeStateListener(PlayerFlagStateListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
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
        PlayerFlagState previous = profile.state();
        profile.setBaseContext(bundle.id(), bundle.mask(), bundle.gamemode().orElse(null));
        dispatchStateChange(playerId, previous, profile.state());
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
        PlayerFlagState previous = profile.state();
        int token = overrideSequence.incrementAndGet();
        profile.addOverride(new PlayerFlagProfile.OverrideEntry(
                token,
                request.enableMask(),
                request.disableMask(),
                request.gamemode().orElse(null)
        ));
        dispatchStateChange(playerId, previous, profile.state());
        return new OverrideScopeHandle(playerId, token);
    }

    public void popOverride(OverrideScopeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        PlayerFlagProfile profile = profiles.get(handle.playerId());
        if (profile == null) {
            return;
        }
        PlayerFlagState previous = profile.state();
        profile.removeOverride(handle.token());
        dispatchStateChange(handle.playerId(), previous, profile.state());
    }

    public void clearOverrides(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PlayerFlagProfile profile = profiles.get(playerId);
        if (profile != null) {
            PlayerFlagState previous = profile.state();
            profile.clearOverrides();
            dispatchStateChange(playerId, previous, profile.state());
        }
    }

    public void clear(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PlayerFlagProfile profile = profiles.remove(playerId);
        if (profile != null) {
            dispatchStateChange(playerId, profile.state(), EMPTY_STATE);
        }
    }

    public boolean allows(UUID playerId, ActionFlag flag) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(flag, "flag");
        PlayerFlagProfile profile = profiles.get(playerId);
        if (profile == null) {
            return true;
        }
        return profile.allows(flag);
    }

    public PlayerFlagSnapshot snapshot(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        PlayerFlagProfile profile = profiles.get(playerId);
        if (profile == null) {
            return new PlayerFlagSnapshot("", ActionFlagMaskUtil.flagsFromMask(0L), List.of(), Optional.empty());
        }
        return profile.snapshot();
    }

    private void dispatchStateChange(UUID playerId, PlayerFlagState previous, PlayerFlagState current) {
        if (previous.equals(current)) {
            return;
        }
        for (PlayerFlagStateListener listener : stateListeners) {
            try {
                listener.onFlagStateChange(playerId, previous, current);
            } catch (Exception ex) {
                logger.warning("Action flag listener error: " + ex.getMessage());
            }
        }
    }
}
