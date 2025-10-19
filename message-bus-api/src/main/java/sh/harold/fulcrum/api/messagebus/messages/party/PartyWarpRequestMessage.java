package sh.harold.fulcrum.api.messagebus.messages.party;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@MessageType("party.warp.request")
public final class PartyWarpRequestMessage implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    private UUID requestId = UUID.randomUUID();
    private UUID partyId;
    private UUID leaderId;
    private String familyId;
    private String variantId;
    private String targetServerId;
    private boolean confirmation;
    private boolean forced;
    private long timestamp = Instant.now().toEpochMilli();

    public PartyWarpRequestMessage() {
        // for jackson
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public UUID getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(UUID leaderId) {
        this.leaderId = leaderId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public void setFamilyId(String familyId) {
        this.familyId = familyId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(String variantId) {
        this.variantId = variantId;
    }

    public String getTargetServerId() {
        return targetServerId;
    }

    public void setTargetServerId(String targetServerId) {
        this.targetServerId = targetServerId;
    }

    public boolean isConfirmation() {
        return confirmation;
    }

    public void setConfirmation(boolean confirmation) {
        this.confirmation = confirmation;
    }

    public boolean isForced() {
        return forced;
    }

    public void setForced(boolean forced) {
        this.forced = forced;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public void validate() {
        if (requestId == null) {
            throw new IllegalStateException("requestId is required");
        }
        if (partyId == null) {
            throw new IllegalStateException("partyId is required");
        }
        if (leaderId == null) {
            throw new IllegalStateException("leaderId is required");
        }
    }
}
