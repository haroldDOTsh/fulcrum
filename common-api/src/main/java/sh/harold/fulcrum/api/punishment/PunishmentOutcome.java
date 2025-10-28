package sh.harold.fulcrum.api.punishment;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of applying a punishment reason to a player.
 */
public record PunishmentOutcome(PunishmentReason reason, int rungBefore, int rungAfter,
                                List<PunishmentEffect> effects) {

    public PunishmentOutcome(PunishmentReason reason,
                             int rungBefore,
                             int rungAfter,
                             List<PunishmentEffect> effects) {
        this.reason = Objects.requireNonNull(reason, "reason");
        this.rungBefore = rungBefore;
        this.rungAfter = rungAfter;
        this.effects = Collections.unmodifiableList(effects);
    }

    public PunishmentLadder getLadder() {
        return reason.getLadder();
    }
}
