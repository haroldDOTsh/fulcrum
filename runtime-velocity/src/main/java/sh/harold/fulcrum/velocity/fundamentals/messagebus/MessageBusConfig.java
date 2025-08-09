package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import java.util.Map;

public class MessageBusConfig {
    
    private final boolean enabled;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final int redisDatabase;
    private final String channelPrefix;
    
    @SuppressWarnings("unchecked")
    public MessageBusConfig(Map<String, Object> config) {
        Map<String, Object> messageBus = (Map<String, Object>) config.getOrDefault("message-bus", Map.of());
        
        this.enabled = (boolean) messageBus.getOrDefault("enabled", true);
        
        Map<String, Object> redis = (Map<String, Object>) messageBus.getOrDefault("redis", Map.of());
        this.redisHost = (String) redis.getOrDefault("host", "localhost");
        this.redisPort = (int) redis.getOrDefault("port", 6379);
        this.redisPassword = (String) redis.getOrDefault("password", "");
        this.redisDatabase = (int) redis.getOrDefault("database", 0);
        this.channelPrefix = (String) messageBus.getOrDefault("channel-prefix", "fulcrum");
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public String getRedisHost() {
        return redisHost;
    }
    
    public int getRedisPort() {
        return redisPort;
    }
    
    public String getRedisPassword() {
        return redisPassword;
    }
    
    public int getRedisDatabase() {
        return redisDatabase;
    }
    
    public String getChannelPrefix() {
        return channelPrefix;
    }
}