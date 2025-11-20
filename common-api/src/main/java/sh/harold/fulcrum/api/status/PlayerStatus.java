package sh.harold.fulcrum.api.status;

import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot of a player's current status.
 *
 * @param playerId            player identifier
 * @param presence            resolved presence (publicly normalised)
 * @param activityBadge       optional mood/activity text (may be null)
 * @param updatedAtEpochMillis last update timestamp in epoch millis
 */
public record PlayerStatus(UUID playerId,
                           PresenceStatus presence,
                           String activityBadge,
                           long updatedAtEpochMillis) {

    public PlayerStatus {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(presence, "presence");
        updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
    }
}

