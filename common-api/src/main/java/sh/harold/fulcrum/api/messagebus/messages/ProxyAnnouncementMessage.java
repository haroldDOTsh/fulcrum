package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Message sent by Velocity proxies to announce their presence to backend servers.
 * This message is broadcast on the global channel so all servers can track available proxies.
 * Includes simplified capacity information with hard and soft caps.
 */
public class ProxyAnnouncementMessage implements Serializable {
    private static final long serialVersionUID = 2L;

    private final String proxyId;
    private final int proxyIndex;
    private final String address;
    private final int hardCap;  // Maximum players allowed
    private final int softCap;  // Preferred player limit
    private final int currentPlayerCount;
    private final long timestamp;

    @JsonCreator
    public ProxyAnnouncementMessage(@JsonProperty("proxyId") String proxyId,
                                    @JsonProperty("proxyIndex") int proxyIndex,
                                    @JsonProperty("hardCap") int hardCap,
                                    @JsonProperty("softCap") int softCap,
                                    @JsonProperty("currentPlayerCount") int currentPlayerCount,
                                    @JsonProperty("address") String address,
                                    @JsonProperty("timestamp") long timestamp) {
        this.proxyId = proxyId;
        this.proxyIndex = proxyIndex;
        this.address = address;
        this.hardCap = hardCap;
        this.softCap = softCap;
        this.currentPlayerCount = currentPlayerCount;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    // Convenience constructor for normal use
    public ProxyAnnouncementMessage(String proxyId, int proxyIndex, int hardCap, int softCap, int currentPlayerCount) {
        this(proxyId, proxyIndex, hardCap, softCap, currentPlayerCount, null, System.currentTimeMillis());
    }

    public String getProxyId() {
        return proxyId;
    }

    public int getProxyIndex() {
        return proxyIndex;
    }

    public String getAddress() {
        return address;
    }

    public int getHardCap() {
        return hardCap;
    }

    public int getSoftCap() {
        return softCap;
    }

    public int getCurrentPlayerCount() {
        return currentPlayerCount;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Check if the proxy is at soft capacity (preferred limit)
     */
    @JsonIgnore
    public boolean isAtSoftCapacity() {
        return currentPlayerCount >= softCap;
    }

    /**
     * Check if the proxy is at hard capacity (maximum limit)
     */
    @JsonIgnore
    public boolean isAtHardCapacity() {
        return currentPlayerCount >= hardCap;
    }

    /**
     * Get the load percentage relative to hard cap
     */
    @JsonIgnore
    public double getLoadPercentage() {
        if (hardCap == 0) return 100.0;
        return (currentPlayerCount * 100.0) / hardCap;
    }

    /**
     * Check if proxy has capacity for more players
     */
    @JsonIgnore
    public boolean hasCapacity() {
        return currentPlayerCount < hardCap;
    }

    @Override
    public String toString() {
        return String.format("ProxyAnnouncement[id=%s, index=%d, players=%d/%d/%d]",
                proxyId, proxyIndex, currentPlayerCount, softCap, hardCap);
    }
}