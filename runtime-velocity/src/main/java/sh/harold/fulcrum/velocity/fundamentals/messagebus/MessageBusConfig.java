package sh.harold.fulcrum.velocity.fundamentals.messagebus;

public class MessageBusConfig {
    
    private String mode = "redis"; // Options: redis, simple
    
    public MessageBusConfig() {
        // Default constructor
    }
    
    public MessageBusConfig(String mode) {
        this.mode = mode;
    }
    
    public String getMode() {
        return mode;
    }
    
    public void setMode(String mode) {
        this.mode = mode;
    }
    
    public boolean isRedisMode() {
        return "redis".equalsIgnoreCase(mode);
    }
    
    public boolean isSimpleMode() {
        return "simple".equalsIgnoreCase(mode);
    }
}