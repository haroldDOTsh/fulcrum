package sh.harold.fulcrum.api.messagebus.messages.punishment;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;

import java.time.Instant;
import java.util.UUID;

@MessageType("fulcrum.registry.punishment.status")
public final class PunishmentStatusMessage implements BaseMessage {

    private UUID punishmentId;
    private UUID playerId;
    private PunishmentStatus status;
    private Instant updatedAt = Instant.now();

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

    public PunishmentStatus getStatus() {
        return status;
    }

    public void setStatus(PunishmentStatus status) {
        this.status = status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public void validate() {
        if (punishmentId == null) {
            throw new IllegalStateException("punishmentId is required");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required");
        }
        if (status == null) {
            throw new IllegalStateException("status is required");
        }
    }
}
