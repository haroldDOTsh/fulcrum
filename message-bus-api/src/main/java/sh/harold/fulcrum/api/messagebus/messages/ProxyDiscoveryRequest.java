package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/**
 * Request sent by backend servers to discover available proxies.
 * Proxies will respond with their current status.
 */
public class ProxyDiscoveryRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String requesterId;
    private final String serverType;
    private final long timestamp;
    
    @JsonCreator
    public ProxyDiscoveryRequest(@JsonProperty("requesterId") String requesterId,
                                 @JsonProperty("serverType") String serverType,
                                 @JsonProperty("timestamp") Long timestamp) {
        this.requesterId = requesterId;
        this.serverType = serverType;
        this.timestamp = timestamp != null ? timestamp : System.currentTimeMillis();
    }
    
    public ProxyDiscoveryRequest(String requesterId, String serverType) {
        this(requesterId, serverType, null);
    }
    
    public String getRequesterId() {
        return requesterId;
    }
    
    public String getServerType() {
        return serverType;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("ProxyDiscoveryRequest[requester=%s, type=%s]", requesterId, serverType);
    }
}