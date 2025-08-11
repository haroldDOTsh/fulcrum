package sh.harold.fulcrum.velocity.config;

public class ServerLifecycleConfig {
    private boolean enabled = true;
    
    // Registration settings
    private boolean registrationEnabled = true;
    private int heartbeatInterval = 30; // seconds
    private int timeoutSeconds = 90; // seconds
    
    // Capacity settings
    private String capacityMode = "dynamic"; // static, dynamic, adaptive
    private int staticCapacity = 100;
    
    // Type detection settings
    private String typeDetectionMode = "auto"; // auto, manual
    private String manualType = "proxy";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }
    
    public void setRegistrationEnabled(boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }
    
    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }
    
    public void setHeartbeatInterval(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }
    
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
    
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
    
    public String getCapacityMode() {
        return capacityMode;
    }
    
    public void setCapacityMode(String capacityMode) {
        this.capacityMode = capacityMode;
    }
    
    public int getStaticCapacity() {
        return staticCapacity;
    }
    
    public void setStaticCapacity(int staticCapacity) {
        this.staticCapacity = staticCapacity;
    }
    
    public String getTypeDetectionMode() {
        return typeDetectionMode;
    }
    
    public void setTypeDetectionMode(String typeDetectionMode) {
        this.typeDetectionMode = typeDetectionMode;
    }
    
    public String getManualType() {
        return manualType;
    }
    
    public void setManualType(String manualType) {
        this.manualType = manualType;
    }
}