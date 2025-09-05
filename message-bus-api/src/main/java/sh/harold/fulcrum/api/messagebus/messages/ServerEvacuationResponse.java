package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;
import java.io.Serializable;

/**
 * Response message sent after server evacuation is complete.
 * Indicates whether all players were successfully evacuated.
 */
@MessageType("server.evacuation.response")
public class ServerEvacuationResponse implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String serverId;
    private final boolean success;
    private final int playersEvacuated;
    private final int playersFailed;
    private final String message;
    private final long timestamp;
    
    @JsonCreator
    public ServerEvacuationResponse(
            @JsonProperty("serverId") String serverId,
            @JsonProperty("success") boolean success,
            @JsonProperty("playersEvacuated") int playersEvacuated,
            @JsonProperty("playersFailed") int playersFailed,
            @JsonProperty("message") String message,
            @JsonProperty("timestamp") Long timestamp) {
        this.serverId = serverId;
        this.success = success;
        this.playersEvacuated = playersEvacuated;
        this.playersFailed = playersFailed;
        this.message = message;
        this.timestamp = timestamp != null ? timestamp : System.currentTimeMillis();
    }
    
    public ServerEvacuationResponse(String serverId, boolean success, int playersEvacuated, int playersFailed, String message) {
        this(serverId, success, playersEvacuated, playersFailed, message, null);
    }
    
    @JsonProperty("serverId")
    public String getServerId() {
        return serverId;
    }
    
    @JsonProperty("success")
    public boolean isSuccess() {
        return success;
    }
    
    @JsonProperty("playersEvacuated")
    public int getPlayersEvacuated() {
        return playersEvacuated;
    }
    
    @JsonProperty("playersFailed")
    public int getPlayersFailed() {
        return playersFailed;
    }
    
    @JsonProperty("message")
    public String getMessage() {
        return message;
    }
    
    @JsonProperty("timestamp")
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