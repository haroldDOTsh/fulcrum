package sh.harold.fulcrum.fundamentals.messagebus;

import org.bukkit.configuration.ConfigurationSection;

public class MessageBusConfig {
    private final boolean playerTrackingEnabled;
    private final int playerLocationRefreshInterval;
    
    public MessageBusConfig(ConfigurationSection config) {
        if (config == null) {
            // Use defaults if no config section
            this.playerTrackingEnabled = true;
            this.playerLocationRefreshInterval = 20;
        } else {
            this.playerTrackingEnabled = config.getBoolean("player-tracking.enabled", true);
            this.playerLocationRefreshInterval = config.getInt("player-tracking.refresh-interval", 20);
        }
    }
    
    public boolean isPlayerTrackingEnabled() {
        return playerTrackingEnabled;
    }
    
    public int getPlayerLocationRefreshInterval() {
        return playerLocationRefreshInterval;
    }
}