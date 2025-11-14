package sh.harold.fulcrum.api.messagebus.messages.social;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.util.UUID;

/**
 * Envelope for cross-network direct messages between players.
 */
@MessageType(value = ChannelConstants.SOCIAL_DIRECT_MESSAGE, version = 1)
public final class DirectMessageEnvelope implements BaseMessage {
    private static final long serialVersionUID = 1L;

    private UUID messageId;
    private UUID senderId;
    private String senderName;
    private UUID recipientId;
    private String recipientName;
    private String componentJson;
    private String plainText;
    private long timestamp;

    public DirectMessageEnvelope() {
        // for jackson
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public void setSenderId(UUID senderId) {
        this.senderId = senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(UUID recipientId) {
        this.recipientId = recipientId;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getComponentJson() {
        return componentJson;
    }

    public void setComponentJson(String componentJson) {
        this.componentJson = componentJson;
    }

    public String getPlainText() {
        return plainText;
    }

    public void setPlainText(String plainText) {
        this.plainText = plainText;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public void validate() {
        if (messageId == null) {
            throw new IllegalStateException("messageId is required");
        }
        if (senderId == null) {
            throw new IllegalStateException("senderId is required");
        }
        if (recipientId == null) {
            throw new IllegalStateException("recipientId is required");
        }
        if ((componentJson == null || componentJson.isBlank())
                && (plainText == null || plainText.isBlank())) {
            throw new IllegalStateException("Either componentJson or plainText must be provided");
        }
        if (timestamp <= 0L) {
            throw new IllegalStateException("timestamp must be positive");
        }
    }
}
