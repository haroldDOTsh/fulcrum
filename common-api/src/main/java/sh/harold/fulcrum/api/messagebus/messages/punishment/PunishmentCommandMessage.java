package sh.harold.fulcrum.api.messagebus.messages.punishment;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import sh.harold.fulcrum.api.punishment.PunishmentReason;

import java.time.Instant;
import java.util.UUID;

@MessageType("fulcrum.registry.punishment.command")
public final class PunishmentCommandMessage implements BaseMessage {

    private UUID commandId = UUID.randomUUID();
    private UUID playerId;
    private String playerName;
    private UUID staffId;
    private String staffName;
    private PunishmentReason reason;
    private Instant issuedAt = Instant.now();

    public UUID getCommandId() {
        return commandId;
    }

    public void setCommandId(UUID commandId) {
        this.commandId = commandId;
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

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    @Override
    public void validate() {
        if (commandId == null) {
            throw new IllegalStateException("commandId is required");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required");
        }
        if (reason == null) {
            throw new IllegalStateException("reason is required");
        }
        if (staffId == null) {
            throw new IllegalStateException("staffId is required");
        }
    }
}
