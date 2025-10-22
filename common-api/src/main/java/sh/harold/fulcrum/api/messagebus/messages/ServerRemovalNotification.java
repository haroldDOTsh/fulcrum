package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;

/**
 * Notification sent to proxies that a server will be removed from the registry.
 * Proxies should remove the server from their internal maps and prevent new connections.
 */
@MessageType("server.removal")
public record ServerRemovalNotification(String serverId, String serverType, String reason,
                                        long timestamp) implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    @JsonCreator
    public ServerRemovalNotification(
            @JsonProperty("serverId") String serverId,
            @JsonProperty("serverType") String serverType,
            @JsonProperty("reason") String reason,
            @JsonProperty("timestamp") Long timestamp) {
        this.serverId = serverId;
        this.serverType = serverType;
        this.reason = reason;
        this.timestamp = timestamp != null ? timestamp : System.currentTimeMillis();
    }

    public ServerRemovalNotification(String serverId, String serverType, String reason) {
        this(serverId, serverType, reason, null);
    }

    @Override
    @JsonProperty("serverId")
    public String serverId() {
        return serverId;
    }

    @Override
    @JsonProperty("serverType")
    public String serverType() {
        return serverType;
    }

    @Override
    @JsonProperty("reason")
    public String reason() {
        return reason;
    }

    @Override
    @JsonProperty("timestamp")
    public long timestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ServerRemovalNotification{" +
                "serverId='" + serverId + '\'' +
                ", serverType='" + serverType + '\'' +
                ", reason='" + reason + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}