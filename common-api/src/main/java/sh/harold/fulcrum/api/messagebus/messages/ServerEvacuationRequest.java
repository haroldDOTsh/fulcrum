package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.MessageType;

import java.io.Serializable;

/**
 * Message sent to backend servers to trigger evacuation of all players.
 * Servers should move all players to available lobby servers upon receiving this message.
 */
@MessageType(value = "server.evacuation.request", version = 1)
public record ServerEvacuationRequest(String serverId, String reason, long timestamp,
                                      int timeoutMillis) implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    public ServerEvacuationRequest(String serverId, String reason) {
        this(serverId, reason, System.currentTimeMillis(), 5000);
    }

    public ServerEvacuationRequest(String serverId, String reason, int timeoutMillis) {
        this(serverId, reason, System.currentTimeMillis(), timeoutMillis);
    }

    public ServerEvacuationRequest {
        if (timestamp <= 0L) {
            timestamp = System.currentTimeMillis();
        }
        if (timeoutMillis <= 0) {
            timeoutMillis = 5000;
        }
    }

    @JsonCreator
    public static ServerEvacuationRequest fromJson(
            @JsonProperty("serverId") String serverId,
            @JsonProperty("reason") String reason,
            @JsonProperty("timestamp") Long timestamp,
            @JsonProperty("timeoutMillis") Integer timeoutMillis) {
        long resolvedTimestamp = timestamp != null ? timestamp : System.currentTimeMillis();
        int resolvedTimeout = timeoutMillis != null ? timeoutMillis : 5000;
        return new ServerEvacuationRequest(serverId, reason, resolvedTimestamp, resolvedTimeout);
    }
}
