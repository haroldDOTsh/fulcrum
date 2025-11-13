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
@MessageType(value = "server.removal", version = 1)
public record ServerRemovalNotification(String serverId, String serverType, String reason,
                                        long timestamp) implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    public ServerRemovalNotification(String serverId, String serverType, String reason) {
        this(serverId, serverType, reason, System.currentTimeMillis());
    }

    public ServerRemovalNotification {
        if (timestamp <= 0L) {
            timestamp = System.currentTimeMillis();
        }
    }

    @JsonCreator
    public static ServerRemovalNotification fromJson(
            @JsonProperty("serverId") String serverId,
            @JsonProperty("serverType") String serverType,
            @JsonProperty("reason") String reason,
            @JsonProperty("timestamp") Long timestamp) {
        long resolvedTimestamp = timestamp != null ? timestamp : System.currentTimeMillis();
        return new ServerRemovalNotification(serverId, serverType, reason, resolvedTimestamp);
    }
}
