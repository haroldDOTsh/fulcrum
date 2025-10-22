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

    public ServerEvacuationResponse(String serverId, boolean success, int playersEvacuated, int playersFailed, String message) {
        this(serverId, success, playersEvacuated, playersFailed, message, System.currentTimeMillis());
    }

    public ServerEvacuationResponse {
        if (timestamp <= 0L) {
            timestamp = System.currentTimeMillis();
        }
    }

    @JsonCreator
    public static ServerEvacuationResponse fromJson(
            @JsonProperty("serverId") String serverId,
            @JsonProperty("success") boolean success,
            @JsonProperty("playersEvacuated") int playersEvacuated,
            @JsonProperty("playersFailed") int playersFailed,
            @JsonProperty("message") String message,
            @JsonProperty("timestamp") Long timestamp) {
        long resolvedTimestamp = timestamp != null ? timestamp : System.currentTimeMillis();
        return new ServerEvacuationResponse(serverId, success, playersEvacuated, playersFailed, message, resolvedTimestamp);
    }
}
