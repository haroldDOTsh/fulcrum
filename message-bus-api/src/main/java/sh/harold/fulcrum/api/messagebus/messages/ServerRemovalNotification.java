package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Notification sent to proxies that a server will be removed from the registry.
 * Proxies should remove the server from their internal maps and prevent new connections.
 */
public class ServerRemovalNotification implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String serverId;
    private final String serverType;
    private final String reason;
    private final long timestamp;
    
    public ServerRemovalNotification(String serverId, String serverType, String reason) {
        this.serverId = serverId;
        this.serverType = serverType;
        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public String getServerType() {
        return serverType;
    }
    
    public String getReason() {
        return reason;
    }
    
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