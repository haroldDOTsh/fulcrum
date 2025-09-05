package sh.harold.fulcrum.api.messagebus.messages;

import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/**
 * Notification sent to proxies that a server will be removed from the registry.
 * Proxies should remove the server from their internal maps and prevent new connections.
 */
@MessageType("server.removal")
public class ServerRemovalNotification implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String serverId;
    private final String serverType;
    private final String reason;
    private final long timestamp;
    
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
    
    @JsonProperty("serverId")
    public String getServerId() {
        return serverId;
    }
    
    @JsonProperty("serverType")
    public String getServerType() {
        return serverType;
    }
    
    @JsonProperty("reason")
    public String getReason() {
        return reason;
    }
    
    @JsonProperty("timestamp")
    public long getTimestamp() {
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