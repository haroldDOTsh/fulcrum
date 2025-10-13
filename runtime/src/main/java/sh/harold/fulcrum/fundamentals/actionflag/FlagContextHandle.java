package sh.harold.fulcrum.fundamentals.actionflag;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Convenience handle for applying a registered context to players.
 */
public final class FlagContextHandle {
    private final ActionFlagService service;
    private final String id;
    private final long mask;

    FlagContextHandle(ActionFlagService service, FlagBundle bundle) {
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(bundle, "bundle");
        this.id = bundle.id();
        this.mask = bundle.mask();
    }

    public String id() {
        return id;
    }

    public long mask() {
        return mask;
    }

    public void applyTo(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        service.applyContext(playerId, id);
    }

    public void applyTo(Collection<UUID> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        service.applyContext(playerIds, id);
    }
}
