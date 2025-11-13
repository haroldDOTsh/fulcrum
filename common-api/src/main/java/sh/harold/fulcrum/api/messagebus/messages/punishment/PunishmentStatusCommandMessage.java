package sh.harold.fulcrum.api.messagebus.messages.punishment;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;

import java.time.Instant;
import java.util.UUID;

@MessageType(value = "fulcrum.registry.punishment.status.command", version = 1)
public final class PunishmentStatusCommandMessage implements BaseMessage {

    private UUID commandId = UUID.randomUUID();
    private UUID punishmentId;
    private PunishmentStatus status;
    private UUID actorId;
    private String actorName;
    private Instant requestedAt = Instant.now();
    private Instant effectiveAt;
    private String note;

    public UUID getCommandId() {
        return commandId;
    }

    public void setCommandId(UUID commandId) {
        this.commandId = commandId;
    }

    public UUID getPunishmentId() {
        return punishmentId;
    }

    public void setPunishmentId(UUID punishmentId) {
        this.punishmentId = punishmentId;
    }

    public PunishmentStatus getStatus() {
        return status;
    }

    public void setStatus(PunishmentStatus status) {
        this.status = status;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    @Override
    public void validate() {
        if (commandId == null) {
            throw new IllegalStateException("commandId is required");
        }
        if (punishmentId == null) {
            throw new IllegalStateException("punishmentId is required");
        }
        if (status == null) {
            throw new IllegalStateException("status is required");
        }
        if (actorId == null) {
            throw new IllegalStateException("actorId is required");
        }
    }
}
