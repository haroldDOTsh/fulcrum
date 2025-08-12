package sh.harold.fulcrum.velocity.config;

/**
 * Simplified server lifecycle configuration using hard/soft cap system.
 * Follows KISS principle - only essential settings.
 */
public class ServerLifecycleConfig {
    private boolean enabled = true;
    private int heartbeatInterval = 30; // seconds
    private int registrationTimeout = 60; // seconds
    private int hardCap = 1000;  // Maximum players allowed (hard limit)
    private int softCap = 500;   // Preferred player limit (soft limit)
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }
    
    public void setHeartbeatInterval(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }
    
    public int getRegistrationTimeout() {
        return registrationTimeout;
    }
    
    public void setRegistrationTimeout(int registrationTimeout) {
        this.registrationTimeout = registrationTimeout;
    }
    
    public int getHardCap() {
        return hardCap;
    }
    
    public void setHardCap(int hardCap) {
        this.hardCap = hardCap;
        // Ensure soft cap is not greater than hard cap
        if (this.softCap > hardCap) {
            this.softCap = hardCap;
        }
    }
    
    public int getSoftCap() {
        return softCap;
    }
    
    public void setSoftCap(int softCap) {
        // Ensure soft cap is not greater than hard cap
        this.softCap = Math.min(softCap, hardCap);
    }
    
    /**
     * Check if the proxy is at soft capacity (preferred limit)
     */
    public boolean isAtSoftCapacity(int currentPlayers) {
        return currentPlayers >= softCap;
    }
    
    /**
     * Check if the proxy is at hard capacity (maximum limit)
     */
    public boolean isAtHardCapacity(int currentPlayers) {
        return currentPlayers >= hardCap;
    }
    
    // Backward compatibility method
    public int getTimeoutSeconds() {
        return registrationTimeout;
    }
    
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.registrationTimeout = timeoutSeconds;
    }
}