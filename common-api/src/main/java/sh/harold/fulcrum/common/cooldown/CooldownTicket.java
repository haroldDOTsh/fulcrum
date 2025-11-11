package sh.harold.fulcrum.common.cooldown;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an accepted cooldown acquisition.
 *
 * @param key       canonical key that was reserved
 * @param expiresAt instant when the cooldown will elapse
 */
public record CooldownTicket(CooldownKey key, Instant expiresAt) {

    public CooldownTicket {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
