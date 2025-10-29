package sh.harold.fulcrum.api.messagebus.messages.punishment;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.time.Instant;
import java.util.UUID;

@MessageType("fulcrum.registry.punishment.expire-request")
public final class PunishmentExpireRequestMessage implements BaseMessage {

    private UUID requestId = UUID.randomUUID();
    private UUID punishmentId;
    private UUID playerId;
    private Instant observedAt = Instant.now();

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

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

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    @Override
    public void validate() {
        if (requestId == null) {
            throw new IllegalStateException("requestId is required");
        }
        if (punishmentId == null) {
            throw new IllegalStateException("punishmentId is required");
        }
        if (playerId == null) {
            throw new IllegalStateException("playerId is required");
        }
    }
}
