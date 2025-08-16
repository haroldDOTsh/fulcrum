package sh.harold.fulcrum.registry.messagebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MessageBus implementation for the Registry Service using Lettuce Redis client.
 * Provides consistent messaging abstraction matching the proxy's MessageBus pattern.
 */
public class RegistryMessageBus {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryMessageBus.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final String serverId;
    
    @FunctionalInterface
    public interface MessageHandler {
        void handle(String channel, Map<String, Object> message);
    }
    
    public RegistryMessageBus(RedisClient redisClient, String serverId) {
        this.redisClient = redisClient;
        this.serverId = serverId;
        this.connection = redisClient.connect();
        this.pubSubConnection = redisClient.connectPubSub();
        
        // Set up the pub/sub listener
        pubSubConnection.addListener(new RegistryRedisListener(this));
    }
    
    /**
     * Broadcast a message to a channel
     */
    public void broadcast(String channel, Object message) {
        try {
            // Create MessageEnvelope-style wrapper
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", message.getClass().getSimpleName());
            envelope.put("senderId", serverId);
            envelope.put("channel", channel);
            envelope.put("timestamp", System.currentTimeMillis());
            envelope.put("messageId", UUID.randomUUID().toString());
            envelope.put("version", 1);
            
            // Handle different message types
            if (message instanceof Map) {
                envelope.put("payload", message);
            } else if (message instanceof String) {
                envelope.put("payload", message);
            } else {
                // Serialize complex objects
                envelope.put("payload", OBJECT_MAPPER.convertValue(message, Map.class));
            }
            
            String serialized = OBJECT_MAPPER.writeValueAsString(envelope);
            RedisCommands<String, String> sync = connection.sync();
            sync.publish(channel, serialized);
            
            LOGGER.debug("Broadcast message to channel {}: {}", channel, message.getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast message", e);
        }
    }
    
    /**
     * Send a direct message to a specific target
     */
    public void send(String targetId, String channel, Object message) {
        try {
            // Create MessageEnvelope-style wrapper with targetId
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", message.getClass().getSimpleName());
            envelope.put("senderId", serverId);
            envelope.put("targetId", targetId);
            envelope.put("channel", channel);
            envelope.put("timestamp", System.currentTimeMillis());
            envelope.put("messageId", UUID.randomUUID().toString());
            envelope.put("version", 1);
            
            // Handle different message types
            if (message instanceof Map) {
                envelope.put("payload", message);
            } else if (message instanceof String) {
                envelope.put("payload", message);
            } else {
                // Serialize complex objects
                envelope.put("payload", OBJECT_MAPPER.convertValue(message, Map.class));
            }
            
            String serialized = OBJECT_MAPPER.writeValueAsString(envelope);
            RedisCommands<String, String> sync = connection.sync();
            
            // Send to both direct channel and general channel
            sync.publish(channel, serialized);
            if (targetId != null && !targetId.isEmpty()) {
                // Also send to target-specific channel
                String targetChannel = channel.contains(":") ? 
                    channel.substring(0, channel.indexOf(":")) + ":" + targetId : 
                    "direct:" + targetId;
                sync.publish(targetChannel, serialized);
            }
            
            LOGGER.debug("Sent message to {} on channel {}", targetId, channel);
        } catch (Exception e) {
            LOGGER.error("Failed to send message", e);
        }
    }
    
    /**
     * Subscribe to a channel
     */
    public void subscribe(String channel, MessageHandler handler) {
        handlers.put(channel, handler);
        
        // Subscribe to the Redis channel
        RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
        sync.subscribe(channel);
        
        LOGGER.info("Subscribed to channel {}", channel);
    }
    
    /**
     * Subscribe to a pattern
     */
    public void subscribePattern(String pattern, MessageHandler handler) {
        handlers.put(pattern, handler);
        
        // Subscribe to the Redis pattern
        RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
        sync.psubscribe(pattern);
        
        LOGGER.info("Subscribed to pattern {}", pattern);
    }
    
    /**
     * Unsubscribe from a channel
     */
    public void unsubscribe(String channel) {
        handlers.remove(channel);
        
        // Unsubscribe from Redis
        RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
        sync.unsubscribe(channel);
        
        LOGGER.info("Unsubscribed from channel {}", channel);
    }
    
    /**
     * Internal method to handle incoming messages
     */
    void handleMessage(String channel, String message) {
        try {
            // Try to parse as envelope first
            Map<String, Object> envelope = null;
            try {
                envelope = OBJECT_MAPPER.readValue(message, Map.class);
            } catch (Exception e) {
                // Not an envelope, treat as raw message
                Map<String, Object> rawMsg = new HashMap<>();
                rawMsg.put("content", message);
                rawMsg.put("channel", channel);
                
                MessageHandler handler = findHandler(channel);
                if (handler != null) {
                    handler.handle(channel, rawMsg);
                }
                return;
            }
            
            // Skip messages from ourselves unless specifically wanted
            String senderId = (String) envelope.get("senderId");
            if (serverId.equals(senderId)) {
                return;
            }
            
            // Find and invoke handler
            MessageHandler handler = findHandler(channel);
            if (handler != null) {
                handler.handle(channel, envelope);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to handle message from channel {}", channel, e);
        }
    }
    
    /**
     * Find a handler for a channel (supports patterns)
     */
    private MessageHandler findHandler(String channel) {
        // Try exact match first
        MessageHandler handler = handlers.get(channel);
        if (handler != null) {
            return handler;
        }
        
        // Try pattern matching
        for (Map.Entry<String, MessageHandler> entry : handlers.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.contains("*")) {
                String regex = pattern.replace("*", ".*");
                if (channel.matches(regex)) {
                    return entry.getValue();
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get the underlying Redis connection
     */
    public StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }
    
    /**
     * Get the underlying PubSub connection
     */
    public StatefulRedisPubSubConnection<String, String> getPubSubConnection() {
        return pubSubConnection;
    }
    
    /**
     * Cleanup resources
     */
    public void shutdown() {
        try {
            pubSubConnection.close();
            connection.close();
            LOGGER.info("MessageBus shutdown complete");
        } catch (Exception e) {
            LOGGER.warn("Error during MessageBus shutdown", e);
        }
    }
}