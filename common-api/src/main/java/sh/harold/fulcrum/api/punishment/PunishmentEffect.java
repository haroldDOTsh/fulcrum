package sh.harold.fulcrum.api.punishment;

import java.time.Duration;
import java.util.Objects;

/**
 * Describes a single effect that should be applied when a punishment tier is reached.
 * Duration is optional and represents how long the effect should last.
 */
public final class PunishmentEffect {

    private final PunishmentEffectType type;
    private final Duration duration;
    private final String message;

    private PunishmentEffect(PunishmentEffectType type, Duration duration, String message) {
        this.type = Objects.requireNonNull(type, "type");
        this.duration = duration;
        this.message = message;
    }

    public static PunishmentEffect warning(String message) {
        return new PunishmentEffect(PunishmentEffectType.WARNING, null, message);
    }

    public static PunishmentEffect mute(Duration duration) {
        return new PunishmentEffect(PunishmentEffectType.MUTE, duration, null);
    }

    public static PunishmentEffect mutePermanent() {
        return new PunishmentEffect(PunishmentEffectType.MUTE, null, null);
    }

    public static PunishmentEffect ban(Duration duration) {
        return new PunishmentEffect(PunishmentEffectType.BAN, duration, null);
    }

    public static PunishmentEffect banPermanent(String message) {
        return new PunishmentEffect(PunishmentEffectType.BAN, null, message);
    }

    public static PunishmentEffect blacklist(String message) {
        return new PunishmentEffect(PunishmentEffectType.BLACKLIST, null, message);
    }

    public static PunishmentEffect manualReview(String message) {
        return new PunishmentEffect(PunishmentEffectType.MANUAL_REVIEW, null, message);
    }

    public static PunishmentEffect appealRequired(String message) {
        return new PunishmentEffect(PunishmentEffectType.APPEAL_REQUIRED, null, message);
    }

    public PunishmentEffectType getType() {
        return type;
    }

    public Duration getDuration() {
        return duration;
    }

    public String getMessage() {
        return message;
    }
}
