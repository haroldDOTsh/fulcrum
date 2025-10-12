package sh.harold.fulcrum.velocity.fundamentals.identity;

import java.util.UUID;

public class PlayerIdentity {

    private final UUID playerId;
    private final String username;
    private final long loginTime;
    private String currentServer;

    public PlayerIdentity(UUID playerId, String username, long loginTime) {
        this.playerId = playerId;
        this.username = username;
        this.loginTime = loginTime;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getUsername() {
        return username;
    }

    public long getLoginTime() {
        return loginTime;
    }

    public String getCurrentServer() {
        return currentServer;
    }

    public void setCurrentServer(String currentServer) {
        this.currentServer = currentServer;
    }
}