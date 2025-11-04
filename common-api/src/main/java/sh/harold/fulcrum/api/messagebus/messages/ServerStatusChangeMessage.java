package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Message broadcast when a server's status changes (AVAILABLE/UNAVAILABLE/DEAD)
 */
public class ServerStatusChangeMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private String serverId;
    private String role;
    private Status oldStatus;
    private Status newStatus;
    private long timestamp;
    // Additional metrics for optimal selection
    private int playerCount;
    private int maxPlayers;
    private double tps;
    public ServerStatusChangeMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public ServerStatusChangeMessage(String serverId, String role,
                                     Status oldStatus, Status newStatus) {
        this();
        this.serverId = serverId;
        this.role = role;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    @Override
    public String toString() {
        return String.format("ServerStatusChangeMessage{serverId='%s', role='%s', %s->%s, players=%d/%d, tps=%.1f}",
                serverId, role, oldStatus, newStatus, playerCount, maxPlayers, tps);
    }

    public enum Status {
        STARTING,
        AVAILABLE,
        UNAVAILABLE,
        RUNNING,
        STOPPING,
        EVACUATING,
        DEAD
    }
}
