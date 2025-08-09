package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.velocity.config.RedisConfig;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class VelocityRedisMessageBus implements MessageBus {
    
    private static final Logger logger = Logger.getLogger(VelocityRedisMessageBus.class.getName());
    private static final int MESSAGE_VERSION = 1;
    
    private final String serverId;
    private final Map<String, MessageHandler> handlers;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final ObjectMapper objectMapper;
    
    public VelocityRedisMessageBus(String serverId, RedisConfig config) {
        this.serverId = serverId;
        this.handlers = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        
        String redisUri = String.format("redis://%s:%d", config.getHost(), config.getPort());
        this.redisClient = RedisClient.create(redisUri);
        this.connection = redisClient.connect();
        this.pubSubConnection = redisClient.connectPubSub();
        
        setupPubSubListener();
    }
    
    private void setupPubSubListener() {
        pubSubConnection.addListener(new io.lettuce.core.pubsub.RedisPubSubListener<String, String>() {
            @Override
            public void message(String channel, String message) {
                handleIncomingMessage(channel, message);
            }
            
            @Override
            public void message(String pattern, String channel, String message) {
                handleIncomingMessage(channel, message);
            }
            
            @Override
            public void subscribed(String channel, long count) {
                logger.info("Subscribed to channel: " + channel);
            }
            
            @Override
            public void psubscribed(String pattern, long count) {
                logger.info("Pattern subscribed: " + pattern);
            }
            
            @Override
            public void unsubscribed(String channel, long count) {
                logger.info("Unsubscribed from channel: " + channel);
            }
            
            @Override
            public void punsubscribed(String pattern, long count) {
                logger.info("Pattern unsubscribed: " + pattern);
            }
        });
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        send(null, type, payload);
    }
    
    @Override
    public void send(String targetServerId, String type, Object payload) {
        try {
            JsonNode payloadNode = objectMapper.valueToTree(payload);
            MessageEnvelope envelope = new MessageEnvelope(
                type,
                serverId,
                targetServerId,
                UUID.randomUUID(),
                System.currentTimeMillis(),
                MESSAGE_VERSION,
                payloadNode
            );
            
            String channel = targetServerId != null ? 
                "fulcrum:server:" + targetServerId : 
                "fulcrum:broadcast";
            String message = objectMapper.writeValueAsString(envelope);
            
            connection.async().publish(channel, message);
        } catch (Exception e) {
            logger.severe("Failed to send message: " + e.getMessage());
        }
    }
    
    @Override
    public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        
        UUID correlationId = UUID.randomUUID();
        String responseChannel = "fulcrum:response:" + correlationId;
        
        // Subscribe to response channel
        MessageHandler responseHandler = envelope -> {
            try {
                if (envelope.getCorrelationId().equals(correlationId)) {
                    Object response = objectMapper.treeToValue(envelope.getPayload(), Object.class);
                    future.complete(response);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };
        
        subscribe(responseChannel, responseHandler);
        
        // Send request
        send(targetServerId, type, payload);
        
        // Cleanup after timeout
        future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .whenComplete((result, error) -> {
                unsubscribe(responseChannel, responseHandler);
            });
        
        return future;
    }
    
    @Override
    public void subscribe(String type, MessageHandler handler) {
        handlers.put(type, handler);
        
        RedisPubSubAsyncCommands<String, String> async = pubSubConnection.async();
        async.subscribe("fulcrum:broadcast");
        async.subscribe("fulcrum:server:" + serverId);
        
        if (type.startsWith("fulcrum:")) {
            async.subscribe(type);
        }
    }
    
    @Override
    public void unsubscribe(String type, MessageHandler handler) {
        handlers.remove(type);
        
        if (handlers.isEmpty() && type.startsWith("fulcrum:")) {
            RedisPubSubAsyncCommands<String, String> async = pubSubConnection.async();
            async.unsubscribe(type);
        }
    }
    
    private void handleIncomingMessage(String channel, String message) {
        try {
            MessageEnvelope envelope = objectMapper.readValue(message, MessageEnvelope.class);
            
            // Check if message is for this server
            if (envelope.getTargetId() != null && !envelope.getTargetId().equals(serverId)) {
                return;
            }
            
            MessageHandler handler = handlers.get(envelope.getType());
            if (handler != null) {
                handler.handle(envelope);
            }
        } catch (Exception e) {
            logger.severe("Failed to handle incoming message: " + e.getMessage());
        }
    }
    
    public void shutdown() {
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }
}