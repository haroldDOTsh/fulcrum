package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;
import java.util.Set;
import java.util.HashSet;

/**
 * Periodic heartbeat message sent by servers to indicate they are alive and their current status.
 * This message is sent every 30 seconds to maintain server registration and health tracking.
 */
public class ServerHeartbeatMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String serverId;
    private String serverType;        // MINI, MEGA, LOBBY, etc.
    private double tps;               // Ticks per second
    private int playerCount;
    private int maxCapacity;
    private long uptime;              // Milliseconds since server start
    private String family;            // From environment file (e.g., "production", "development")
    private String role;              // From environment file (e.g., "game", "lobby", "auth")
    private Set<String> availablePools; // For pool-specific servers
    private long timestamp;
    private long responseTime;        // Response time in milliseconds for optimal selection
    
    public ServerHeartbeatMessage() {
        this.availablePools = new HashSet<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public ServerHeartbeatMessage(String serverId, String serverType) {
        this();
        this.serverId = serverId;
        this.serverType = serverType;
    }
    
    // Getters and setters
    public String getServerId() {
        return serverId;
    }
    
    public void setServerId(String serverId) {
        this.serverId = serverId;
    }
    
    public String getServerType() {
        return serverType;
    }
    
    public void setServerType(String serverType) {
        this.serverType = serverType;
    }
    
    public double getTps() {
        return tps;
    }
    
    public void setTps(double tps) {
        this.tps = tps;
    }
    
    public int getPlayerCount() {
        return playerCount;
    }
    
    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }
    
    public int getMaxCapacity() {
        return maxCapacity;
    }
    
    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }
    
    public long getUptime() {
        return uptime;
    }
    
    public void setUptime(long uptime) {
        this.uptime = uptime;
    }
    
    public String getFamily() {
        return family;
    }
    
    public void setFamily(String family) {
        this.family = family;
    }
    
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
    }
    
    public Set<String> getAvailablePools() {
        return availablePools;
    }
    
    public void setAvailablePools(Set<String> availablePools) {
        this.availablePools = availablePools;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public long getResponseTime() {
        return responseTime;
    }
    
    public void setResponseTime(long responseTime) {
        this.responseTime = responseTime;
    }
    
    /**
     * Checks if this heartbeat is stale (older than the timeout period)
     * @param timeoutMs Timeout in milliseconds (e.g., 90000 for 90 seconds)
     * @return true if the heartbeat is stale
     */
    public boolean isStale(long timeoutMs) {
        return System.currentTimeMillis() - timestamp > timeoutMs;
    }
    
    @Override
    public String toString() {
        return String.format("ServerHeartbeatMessage[id=%s, type=%s, tps=%.2f, players=%d/%d, uptime=%dms]",
                serverId, serverType, tps, playerCount, maxCapacity, uptime);
    }
}