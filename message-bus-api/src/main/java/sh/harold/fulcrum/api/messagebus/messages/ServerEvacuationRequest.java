package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Message sent to backend servers to trigger evacuation of all players.
 * Servers should move all players to available lobby servers upon receiving this message.
 */
public class ServerEvacuationRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String serverId;
    private final String reason;
    private final long timestamp;
    private final int timeoutMillis;
    
    public ServerEvacuationRequest(String serverId, String reason) {
        this(serverId, reason, 5000); // Default 5 second timeout
    }
    
    public ServerEvacuationRequest(String serverId, String reason, int timeoutMillis) {
        this.serverId = serverId;
        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
        this.timeoutMillis = timeoutMillis;
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public String getReason() {
        return reason;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public int getTimeoutMillis() {
        return timeoutMillis;
    }
    
    @Override
    public String toString() {
        return "ServerEvacuationRequest{" +
                "serverId='" + serverId + '\'' +
                ", reason='" + reason + '\'' +
                ", timestamp=" + timestamp +
                ", timeoutMillis=" + timeoutMillis +
                '}';
    }
}