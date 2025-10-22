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
public record ServerEvacuationResponse(String serverId, boolean success, int playersEvacuated, int playersFailed,
                                       String message, long timestamp) implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

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

    @Override
    @JsonProperty("serverId")
    public String serverId() {
        return serverId;
    }

    @Override
    @JsonProperty("success")
    public boolean success() {
        return success;
    }

    @Override
    @JsonProperty("playersEvacuated")
    public int playersEvacuated() {
        return playersEvacuated;
    }

    @Override
    @JsonProperty("playersFailed")
    public int playersFailed() {
        return playersFailed;
    }

    @Override
    @JsonProperty("message")
    public String message() {
        return message;
    }

    @Override
    @JsonProperty("timestamp")
    public long timestamp() {
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