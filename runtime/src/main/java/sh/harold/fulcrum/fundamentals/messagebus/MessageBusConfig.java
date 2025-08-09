package sh.harold.fulcrum.fundamentals.messagebus;

import org.bukkit.configuration.ConfigurationSection;

public class MessageBusConfig {
    private final String serverId;
    private final String proxyId;
    private final boolean playerTrackingEnabled;
    private final int playerLocationRefreshInterval;
    
    public MessageBusConfig(ConfigurationSection config) {
        if (config == null) {
            // Use defaults if no config section
            this.serverId = "server-" + System.currentTimeMillis();
            this.proxyId = "proxy-default";
            this.playerTrackingEnabled = true;
            this.playerLocationRefreshInterval = 20;
        } else {
            this.serverId = config.getString("server-id", "server-" + System.currentTimeMillis());
            this.proxyId = config.getString("proxy-id", "proxy-default");
            this.playerTrackingEnabled = config.getBoolean("player-tracking.enabled", true);
            this.playerLocationRefreshInterval = config.getInt("player-tracking.refresh-interval", 20);
        }
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public String getProxyId() {
        return proxyId;
    }
    
    public boolean isPlayerTrackingEnabled() {
        return playerTrackingEnabled;
    }
    
    public int getPlayerLocationRefreshInterval() {
        return playerLocationRefreshInterval;
    }
}