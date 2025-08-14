package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Message broadcast when a server's status changes (AVAILABLE/UNAVAILABLE/DEAD)
 */
public class ServerStatusChangeMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum Status {
        AVAILABLE,
        UNAVAILABLE, 
        DEAD
    }
    
    private String serverId;
    private String serverFamily;
    private Status oldStatus;
    private Status newStatus;
    private long timestamp;
    
    // Additional metrics for optimal selection
    private int playerCount;
    private int maxPlayers;
    private double tps;
    private long responseTime;
    
    public ServerStatusChangeMessage() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public ServerStatusChangeMessage(String serverId, String serverFamily, 
                                    Status oldStatus, Status newStatus) {
        this();
        this.serverId = serverId;
        this.serverFamily = serverFamily;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
    
    // Getters and setters
    public String getServerId() {
        return serverId;
    }
    
    public void setServerId(String serverId) {
        this.serverId = serverId;
    }
    
    public String getServerFamily() {
        return serverFamily;
    }
    
    public void setServerFamily(String serverFamily) {
        this.serverFamily = serverFamily;
    }
    
    public Status getOldStatus() {
        return oldStatus;
    }
    
    public void setOldStatus(Status oldStatus) {
        this.oldStatus = oldStatus;
    }
    
    public Status getNewStatus() {
        return newStatus;
    }
    
    public void setNewStatus(Status newStatus) {
        this.newStatus = newStatus;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public int getPlayerCount() {
        return playerCount;
    }
    
    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }
    
    public int getMaxPlayers() {
        return maxPlayers;
    }
    
    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }
    
    public double getTps() {
        return tps;
    }
    
    public void setTps(double tps) {
        this.tps = tps;
    }
    
    public long getResponseTime() {
        return responseTime;
    }
    
    public void setResponseTime(long responseTime) {
        this.responseTime = responseTime;
    }
    
    @Override
    public String toString() {
        return String.format("ServerStatusChangeMessage{serverId='%s', family='%s', %s->%s, players=%d/%d, tps=%.1f}",
                serverId, serverFamily, oldStatus, newStatus, playerCount, maxPlayers, tps);
    }
}