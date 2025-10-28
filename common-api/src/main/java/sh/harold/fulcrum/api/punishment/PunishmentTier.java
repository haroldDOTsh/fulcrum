package sh.harold.fulcrum.api.punishment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the list of effects that should be applied when a player reaches a specific rung for a reason.
 */
public final class PunishmentTier {

    private final int rung;
    private final List<PunishmentEffect> effects;

    private PunishmentTier(int rung, List<PunishmentEffect> effects) {
        if (rung <= 0) {
            throw new IllegalArgumentException("rung must be positive");
        }
        if (effects == null || effects.isEmpty()) {
            throw new IllegalArgumentException("effects must contain at least one entry");
        }
        this.rung = rung;
        this.effects = Collections.unmodifiableList(new ArrayList<>(effects));
    }

    public static PunishmentTier of(int rung, List<PunishmentEffect> effects) {
        return new PunishmentTier(rung, effects);
    }

    public static PunishmentTier of(int rung, PunishmentEffect... effects) {
        List<PunishmentEffect> list = new ArrayList<>(effects.length);
        for (PunishmentEffect effect : effects) {
            list.add(Objects.requireNonNull(effect, "effect"));
        }
        return new PunishmentTier(rung, list);
    }

    public int getRung() {
        return rung;
    }

    public List<PunishmentEffect> getEffects() {
        return effects;
    }
}
