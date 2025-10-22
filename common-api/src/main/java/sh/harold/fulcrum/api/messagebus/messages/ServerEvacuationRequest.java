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
@MessageType("server.evacuation.request")
public record ServerEvacuationRequest(String serverId, String reason, long timestamp,
                                      int timeoutMillis) implements BaseMessage, Serializable {
    private static final long serialVersionUID = 1L;

    @JsonCreator
    public ServerEvacuationRequest(
            @JsonProperty("serverId") String serverId,
            @JsonProperty("reason") String reason,
            @JsonProperty("timestamp") Long timestamp,
            @JsonProperty("timeoutMillis") Integer timeoutMillis) {
        this.serverId = serverId;
        this.reason = reason;
        this.timestamp = timestamp != null ? timestamp : System.currentTimeMillis();
        this.timeoutMillis = timeoutMillis != null ? timeoutMillis : 5000;
    }

    public ServerEvacuationRequest(String serverId, String reason) {
        this(serverId, reason, null, null);
    }

    public ServerEvacuationRequest(String serverId, String reason, int timeoutMillis) {
        this(serverId, reason, null, timeoutMillis);
    }

    @Override
    @JsonProperty("serverId")
    public String serverId() {
        return serverId;
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
    @JsonProperty("timeoutMillis")
    public int timeoutMillis() {
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