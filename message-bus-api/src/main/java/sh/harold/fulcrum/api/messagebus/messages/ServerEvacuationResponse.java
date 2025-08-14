package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Response message sent after server evacuation is complete.
 * Indicates whether all players were successfully evacuated.
 */
public class ServerEvacuationResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String serverId;
    private final boolean success;
    private final int playersEvacuated;
    private final int playersFailed;
    private final String message;
    private final long timestamp;
    
    public ServerEvacuationResponse(String serverId, boolean success, int playersEvacuated, int playersFailed, String message) {
        this.serverId = serverId;
        this.success = success;
        this.playersEvacuated = playersEvacuated;
        this.playersFailed = playersFailed;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public int getPlayersEvacuated() {
        return playersEvacuated;
    }
    
    public int getPlayersFailed() {
        return playersFailed;
    }
    
    public String getMessage() {
        return message;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return "ServerEvacuationResponse{" +
                "serverId='" + serverId + '\'' +
                ", success=" + success +
                ", playersEvacuated=" + playersEvacuated +
                ", playersFailed=" + playersFailed +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}