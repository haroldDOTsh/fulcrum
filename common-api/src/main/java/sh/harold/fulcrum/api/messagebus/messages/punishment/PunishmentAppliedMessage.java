package sh.harold.fulcrum.api.messagebus.messages.punishment;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import sh.harold.fulcrum.api.punishment.PunishmentEffectType;
import sh.harold.fulcrum.api.punishment.PunishmentLadder;
import sh.harold.fulcrum.api.punishment.PunishmentReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@MessageType(value = "fulcrum.registry.punishment.applied", version = 1)
public final class PunishmentAppliedMessage implements BaseMessage {

    private final List<Effect> effects = new ArrayList<>();
    private UUID punishmentId;
    private UUID playerId;
    private String playerName;
    private UUID staffId;
    private String staffName;
    private PunishmentReason reason;
    private PunishmentLadder ladder;
    private int rungBefore;
    private int rungAfter;
    private Instant issuedAt = Instant.now();

    public UUID getPunishmentId() {
        return punishmentId;
    }

    public void setPunishmentId(UUID punishmentId) {
        this.punishmentId = punishmentId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public void setStaffId(UUID staffId) {
        this.staffId = staffId;
    }

    public String getStaffName() {
        return staffName;
    }

    public void setStaffName(String staffName) {
        this.staffName = staffName;
    }

    public PunishmentReason getReason() {
        return reason;
    }

    public void setReason(PunishmentReason reason) {
        this.reason = reason;
    }

    public PunishmentLadder getLadder() {
        return ladder;
    }

    public void setLadder(PunishmentLadder ladder) {
        this.ladder = ladder;
    }

    public int getRungBefore() {
        return rungBefore;
    }

    public void setRungBefore(int rungBefore) {
        this.rungBefore = rungBefore;
    }

    public int getRungAfter() {
        return rungAfter;
    }

    public void setRungAfter(int rungAfter) {
        this.rungAfter = rungAfter;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public List<Effect> getEffects() {
        return Collections.unmodifiableList(effects);
    }

    public void setEffects(List<Effect> newEffects) {
        effects.clear();
        if (newEffects != null) {
            effects.addAll(newEffects);
        }
    }

    public void addEffect(Effect effect) {
        if (effect != null) {
            effects.add(effect);
        }
    }

    @Override
    public void validate() {
        if (punishmentId == null) {
            throw new IllegalStateException("punishmentId is required");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required");
        }
        if (reason == null) {
            throw new IllegalStateException("reason is required");
        }
        if (ladder == null) {
            throw new IllegalStateException("ladder is required");
        }
    }

    public static final class Effect {
        private PunishmentEffectType type;
        private long durationSeconds;
        private Instant expiresAt;
        private String message;

        public Effect() {
        }

        public Effect(PunishmentEffectType type, long durationSeconds, Instant expiresAt, String message) {
            this.type = type;
            this.durationSeconds = durationSeconds;
            this.expiresAt = expiresAt;
            this.message = message;
        }

        public PunishmentEffectType getType() {
            return type;
        }

        public void setType(PunishmentEffectType type) {
            this.type = type;
        }

        public long getDurationSeconds() {
            return durationSeconds;
        }

        public void setDurationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
