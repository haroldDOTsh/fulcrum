package sh.harold.fulcrum.api.messagebus.messages.chat;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.util.UUID;

/**
 * Payload for cross-network chat channel delivery.
 */
@MessageType("chat.channel.message")
public final class ChatChannelMessage implements BaseMessage {
    private static final long serialVersionUID = 1L;

    private int schemaVersion = 1;
    private UUID messageId;
    private String channelId;
    private UUID senderId;
    private String componentJson;
    private String plainText;
    private long timestamp;

    public ChatChannelMessage() {
        // for jackson
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public void setSenderId(UUID senderId) {
        this.senderId = senderId;
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
        if (schemaVersion <= 0) {
            throw new IllegalStateException("schemaVersion must be positive");
        }
        if (messageId == null) {
            throw new IllegalStateException("messageId is required");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalStateException("channelId is required");
        }
        if (senderId == null) {
            throw new IllegalStateException("senderId is required");
        }
        if ((componentJson == null || componentJson.isBlank())
                && (plainText == null || plainText.isBlank())) {
            throw new IllegalStateException("Either componentJson or plainText must be provided");
        }
        if (timestamp <= 0L) {
            throw new IllegalStateException("timestamp must be provided");
        }
    }
}
