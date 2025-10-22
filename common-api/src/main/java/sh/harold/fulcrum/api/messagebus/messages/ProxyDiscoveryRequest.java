package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Request sent by backend servers to discover available proxies.
 * Proxies will respond with their current status.
 */
public record ProxyDiscoveryRequest(String requesterId, String serverType, long timestamp) implements Serializable {
    private static final long serialVersionUID = 1L;

    public ProxyDiscoveryRequest(String requesterId, String serverType) {
        this(requesterId, serverType, System.currentTimeMillis());
    }

    public ProxyDiscoveryRequest {
        if (timestamp <= 0L) {
            timestamp = System.currentTimeMillis();
        }
    }

    @JsonCreator
    public static ProxyDiscoveryRequest fromJson(@JsonProperty("requesterId") String requesterId,
                                                 @JsonProperty("serverType") String serverType,
                                                 @JsonProperty("timestamp") Long timestamp) {
        long resolvedTimestamp = timestamp != null ? timestamp : System.currentTimeMillis();
        return new ProxyDiscoveryRequest(requesterId, serverType, resolvedTimestamp);
    }
}
