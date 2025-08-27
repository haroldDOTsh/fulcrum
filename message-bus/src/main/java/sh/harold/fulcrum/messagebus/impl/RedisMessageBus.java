package sh.harold.fulcrum.messagebus.impl;

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import sh.harold.fulcrum.messagebus.MessageEnvelope;
import sh.harold.fulcrum.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.messagebus.redis.RedisConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Redis-based implementation of MessageBus for distributed messaging.
 * Uses Redis pub/sub for real-time message delivery across multiple servers.
 */
public class RedisMessageBus extends AbstractMessageBus {
    
    private static final String CHANNEL_PREFIX = "fulcrum:messagebus:";
    private static final String BROADCAST_CHANNEL = CHANNEL_PREFIX + "broadcast";
    private static final String DIRECT_CHANNEL_PREFIX = CHANNEL_PREFIX + "direct:";
    
    private final RedisConnectionManager redisManager;
    private final ObjectMapper objectMapper;
    private final Set<String> subscribedChannels;
    private volatile boolean running = true;
    
    public RedisMessageBus(MessageBusAdapter adapter, RedisConnectionManager redisManager) {
        super(adapter);
        this.redisManager = redisManager;
        this.objectMapper = new ObjectMapper();
        this.subscribedChannels = new HashSet<>();
        
        initialize();
    }
    
    private void initialize() {
        try {
            // Subscribe to broadcast channel
            subscribeToRedisChannel(BROADCAST_CHANNEL);
            
            // Subscribe to direct channel for this server
            String directChannel = DIRECT_CHANNEL_PREFIX + serverIdentifier;
            subscribeToRedisChannel(directChannel);
            
            // Add pub/sub listener
            StatefulRedisPubSubConnection<String, String> pubSubConnection = 
                redisManager.getPubSubConnection();
            
            pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    handleRedisMessage(channel, message);
                }
            });
            
            // Schedule periodic health check
            scheduler.scheduleAtFixedRate(this::healthCheck, 30, 30, TimeUnit.SECONDS);
            
            // Schedule periodic cleanup
            scheduler.scheduleAtFixedRate(this::cleanupSubscriptions, 60, 60, TimeUnit.SECONDS);
            
            logger.info("RedisMessageBus initialized for server: " + serverIdentifier);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize RedisMessageBus", e);
            throw new RuntimeException("Failed to initialize RedisMessageBus", e);
        }
    }
    
    @Override
    public CompletableFuture<Void> broadcast(String messageType, Object payload) {
        return CompletableFuture.runAsync(() -> {
            try {
                MessageEnvelope envelope = new MessageEnvelope(
                    messageType, serverIdentifier, payload
                );
                
                String serialized = serializeEnvelope(envelope);
                
                // Publish to broadcast channel
                redisManager.getPubSubCommands()
                    .publish(BROADCAST_CHANNEL, serialized)
                    .whenComplete((count, error) -> {
                        if (error != null) {
                            logger.log(Level.WARNING, "Failed to broadcast message", error);
                        } else {
                            logger.log(Level.FINE, String.format(
                                "Broadcast message %s to %d subscribers", messageType, count
                            ));
                        }
                    });
                    
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to broadcast message", e);
                throw new RuntimeException(e);
            }
        }, scheduler);
    }
    
    @Override
    public CompletableFuture<Void> send(String targetServer, String messageType, Object payload) {
        return CompletableFuture.runAsync(() -> {
            try {
                MessageEnvelope envelope = new MessageEnvelope(
                    messageType, serverIdentifier, targetServer, payload, null
                );
                
                String serialized = serializeEnvelope(envelope);
                String targetChannel = DIRECT_CHANNEL_PREFIX + targetServer;
                
                // Publish to target server's direct channel
                redisManager.getPubSubCommands()
                    .publish(targetChannel, serialized)
                    .whenComplete((count, error) -> {
                        if (error != null) {
                            logger.log(Level.WARNING, "Failed to send message to " + targetServer, error);
                        } else if (count == 0) {
                            logger.log(Level.WARNING, "No subscribers for server: " + targetServer);
                        } else {
                            logger.log(Level.FINE, String.format(
                                "Sent message %s to %s", messageType, targetServer
                            ));
                        }
                    });
                    
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to send message", e);
                throw new RuntimeException(e);
            }
        }, scheduler);
    }
    
    @Override
    public boolean isConnected() {
        return running && redisManager.isConnected();
    }
    
    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            logger.info("Shutting down RedisMessageBus for: " + serverIdentifier);
            
            running = false;
            
            // Unsubscribe from all channels
            for (String channel : subscribedChannels) {
                try {
                    redisManager.getPubSubCommands().unsubscribe(channel);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error unsubscribing from channel: " + channel, e);
                }
            }
            subscribedChannels.clear();
            
            // Shutdown Redis connections
            redisManager.shutdown();
            
            logger.info("RedisMessageBus shutdown complete");
        });
    }
    
    /**
     * Subscribe to a Redis channel.
     */
    private void subscribeToRedisChannel(String channel) {
        try {
            redisManager.getPubSubCommands().subscribe(channel);
            subscribedChannels.add(channel);
            logger.log(Level.FINE, "Subscribed to Redis channel: " + channel);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to subscribe to channel: " + channel, e);
        }
    }
    
    /**
     * Handle incoming Redis pub/sub message.
     */
    private void handleRedisMessage(String channel, String message) {
        if (!running) {
            return;
        }
        
        try {
            MessageEnvelope envelope = deserializeEnvelope(message);
            
            // Don't process our own messages unless we have local subscribers
            if (serverIdentifier.equals(envelope.getSourceServer())) {
                boolean hasSubscribers = directSubscriptions.containsKey(envelope.getMessageType()) ||
                                        patternSubscriptions.keySet().stream()
                                            .anyMatch(p -> p.matcher(envelope.getMessageType()).matches());
                if (!hasSubscribers) {
                    return;
                }
            }
            
            // Process the message
            processIncomingMessage(envelope);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing Redis message", e);
        }
    }
    
    /**
     * Serialize MessageEnvelope to JSON.
     */
    private String serializeEnvelope(MessageEnvelope envelope) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("id", envelope.getId());
            data.put("messageType", envelope.getMessageType());
            data.put("sourceServer", envelope.getSourceServer());
            data.put("targetServer", envelope.getTargetServer());
            data.put("timestamp", envelope.getTimestamp().toString());
            data.put("correlationId", envelope.getCorrelationId());
            
            // Serialize payload using codec registry
            String serializedPayload = codecRegistry.serialize(
                envelope.getMessageType(), 
                envelope.getPayload()
            );
            data.put("payload", serializedPayload);
            
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize envelope", e);
        }
    }
    
    /**
     * Deserialize MessageEnvelope from JSON.
     */
    private MessageEnvelope deserializeEnvelope(String json) {
        try {
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            
            String messageType = (String) data.get("messageType");
            String sourceServer = (String) data.get("sourceServer");
            String targetServer = (String) data.get("targetServer");
            String correlationId = (String) data.get("correlationId");
            String serializedPayload = (String) data.get("payload");
            
            // Deserialize payload using codec registry
            Object payload = codecRegistry.deserialize(messageType, serializedPayload);
            
            return new MessageEnvelope(
                messageType, sourceServer, targetServer, payload, correlationId
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize envelope", e);
        }
    }
    
    /**
     * Perform periodic health check.
     */
    private void healthCheck() {
        if (!running) {
            return;
        }
        
        try {
            if (!redisManager.ping()) {
                logger.warning("Redis health check failed, attempting reconnection...");
                // Connection manager will handle reconnection
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Health check error", e);
        }
    }
}