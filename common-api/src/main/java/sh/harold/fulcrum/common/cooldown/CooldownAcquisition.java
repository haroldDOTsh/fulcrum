package sh.harold.fulcrum.common.cooldown;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of attempting to reserve a cooldown slot.
 */
public sealed interface CooldownAcquisition permits CooldownAcquisition.Accepted, CooldownAcquisition.Rejected {

    record Accepted(CooldownTicket ticket) implements CooldownAcquisition {
        public Accepted {
            Objects.requireNonNull(ticket, "ticket");
        }
    }

    record Rejected(Duration remaining) implements CooldownAcquisition {
        public Rejected {
            Objects.requireNonNull(remaining, "remaining");
        }
    }
}
