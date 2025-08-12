package sh.harold.fulcrum.velocity.fundamentals.messagebus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.velocity.config.RedisConfig;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Velocity-specific Redis message bus implementation with full request-response support.
 * Uses Lettuce client for Redis operations, consistent with Paper implementation.
 */
public class VelocityRedisMessageBus implements MessageBus {
    private static final Logger logger = Logger.getLogger(VelocityRedisMessageBus.class.getName());
    
    private static final String BROADCAST_CHANNEL = "fulcrum:broadcast";
    private static final String SERVER_CHANNEL_PREFIX = "fulcrum:server:";
    private static final String REQUEST_CHANNEL_PREFIX = "fulcrum:request:";
    private static final String RESPONSE_CHANNEL_PREFIX = "fulcrum:response:";
    
    private final ProxyServer proxy;
    private final String serverId;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final ObjectMapper objectMapper;
    
    // Store handlers by message type
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();
    
    // Request-response tracking
    private final Map<UUID, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private volatile boolean running = true;
    
    /**
     * Creates a new VelocityRedisMessageBus instance.
     * 
     * @param serverId The unique server identifier
     * @param proxy The Velocity proxy server instance
     * @param config Redis configuration
     */
    public VelocityRedisMessageBus(String serverId, ProxyServer proxy, RedisConfig config) {
        this.serverId = serverId;
        this.proxy = proxy;
        this.objectMapper = new ObjectMapper();
        // Configure ObjectMapper to ignore unknown properties during deserialization
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        try {
            // Build Redis URI
            RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(config.getHost())
                .withPort(config.getPort())
                .withDatabase(config.getDatabase())
                .withTimeout(config.getConnectionTimeout());
            
            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                uriBuilder.withPassword(config.getPassword().toCharArray());
            }
            
            RedisURI redisUri = uriBuilder.build();
            
            // Create Redis client and connections
            this.redisClient = RedisClient.create(redisUri);
            this.connection = redisClient.connect();
            this.pubSubConnection = redisClient.connectPubSub();
            
            // Set up message listener
            setupMessageListener();
            
            // Subscribe to channels
            subscribeToChannels();
            
            logger.info("VelocityRedisMessageBus initialized with server ID: " + serverId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize Redis message bus", e);
            throw new RuntimeException("Failed to initialize Redis message bus", e);
        }
    }
    
    /**
     * Simplified constructor for backward compatibility.
     */
    public VelocityRedisMessageBus(String serverId, ProxyServer proxy) {
        this(serverId, proxy, RedisConfig.defaults());
    }
    
    private void setupMessageListener() {
        pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                if (!running) return;
                
                try {
                    MessageEnvelope envelope = deserializeEnvelope(message);
                    
                    // Handle response messages
                    if (channel.startsWith(RESPONSE_CHANNEL_PREFIX)) {
                        handleResponse(envelope);
                        return;
                    }
                    
                    // Handle request messages
                    if (channel.startsWith(REQUEST_CHANNEL_PREFIX)) {
                        handleRequest(envelope);
                        return;
                    }
                    
                    // Handle regular messages
                    handleMessage(envelope);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to process message from channel: " + channel, e);
                }
            }
        });
    }
    
    private void subscribeToChannels() {
        RedisPubSubCommands<String, String> pubSubCommands = pubSubConnection.sync();
        
        // Subscribe to broadcast channel
        pubSubCommands.subscribe(BROADCAST_CHANNEL);
        
        // Subscribe to server-specific channel
        pubSubCommands.subscribe(SERVER_CHANNEL_PREFIX + serverId);
        
        // Subscribe to request channel for this server
        pubSubCommands.subscribe(REQUEST_CHANNEL_PREFIX + serverId);
        
        // Subscribe to response channel for this server
        pubSubCommands.subscribe(RESPONSE_CHANNEL_PREFIX + serverId);
        
        logger.info("Subscribed to Redis channels for server: " + serverId);
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        try {
            MessageEnvelope envelope = createEnvelope(type, null, payload);
            String serialized = serializeEnvelope(envelope);
            
            RedisCommands<String, String> commands = connection.sync();
            commands.publish(BROADCAST_CHANNEL, serialized);
            
            logger.fine("Broadcasted message type: " + type);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to broadcast message", e);
        }
    }
    
    @Override
    public void send(String targetServerId, String type, Object payload) {
        try {
            MessageEnvelope envelope = createEnvelope(type, targetServerId, payload);
            String serialized = serializeEnvelope(envelope);
            
            RedisCommands<String, String> commands = connection.sync();
            String channel = SERVER_CHANNEL_PREFIX + targetServerId;
            commands.publish(channel, serialized);
            
            logger.fine("Sent message type: " + type + " to server: " + targetServerId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send message to server: " + targetServerId, e);
        }
    }
    
    @Override
    public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
        UUID correlationId = UUID.randomUUID();
        CompletableFuture<Object> future = new CompletableFuture<>();
        
        // Store pending request
        pendingRequests.put(correlationId, future);
        
        // Schedule timeout
        scheduler.schedule(() -> {
            CompletableFuture<Object> pending = pendingRequests.remove(correlationId);
            if (pending != null && !pending.isDone()) {
                pending.completeExceptionally(new TimeoutException("Request timed out"));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        
        try {
            // Create request envelope with correlation ID
            MessageEnvelope envelope = new MessageEnvelope(
                type,
                serverId,
                targetServerId,
                correlationId,
                System.currentTimeMillis(),
                1,
                objectMapper.valueToTree(payload)
            );
            
            String serialized = serializeEnvelope(envelope);
            
            RedisCommands<String, String> commands = connection.sync();
            String channel = REQUEST_CHANNEL_PREFIX + targetServerId;
            commands.publish(channel, serialized);
            
            logger.fine("Sent request to server: " + targetServerId + " with correlation ID: " + correlationId);
        } catch (Exception e) {
            pendingRequests.remove(correlationId);
            future.completeExceptionally(e);
            logger.log(Level.WARNING, "Failed to send request", e);
        }
        
        return future;
    }
    
    @Override
    public void subscribe(String type, MessageHandler handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        logger.fine("Subscribed handler for type: " + type);
    }
    
    @Override
    public void unsubscribe(String type, MessageHandler handler) {
        List<MessageHandler> typeHandlers = handlers.get(type);
        if (typeHandlers != null) {
            typeHandlers.remove(handler);
            if (typeHandlers.isEmpty()) {
                handlers.remove(type);
            }
        }
        logger.fine("Unsubscribed handler for type: " + type);
    }
    
    private void handleMessage(MessageEnvelope envelope) {
        List<MessageHandler> typeHandlers = handlers.get(envelope.getType());
        if (typeHandlers != null) {
            for (MessageHandler handler : typeHandlers) {
                try {
                    handler.handle(envelope);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling message of type: " + envelope.getType(), e);
                }
            }
        }
    }
    
    private void handleRequest(MessageEnvelope envelope) {
        // Check if we have a handler for this request type
        List<MessageHandler> typeHandlers = handlers.get(envelope.getType());
        if (typeHandlers != null && !typeHandlers.isEmpty()) {
            // Handle the request and potentially send a response
            for (MessageHandler handler : typeHandlers) {
                try {
                    // Execute handler - it should handle sending response if needed
                    handler.handle(envelope);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling request of type: " + envelope.getType(), e);
                    // Could send error response here if needed
                    sendErrorResponse(envelope);
                }
            }
        } else {
            // No handler found, send error response
            sendErrorResponse(envelope);
        }
    }
    
    private void handleResponse(MessageEnvelope envelope) {
        UUID correlationId = envelope.getCorrelationId();
        if (correlationId != null) {
            CompletableFuture<Object> future = pendingRequests.remove(correlationId);
            if (future != null) {
                try {
                    // Extract the response payload
                    Object response = objectMapper.treeToValue(envelope.getPayload(), Object.class);
                    future.complete(response);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        }
    }
    
    /**
     * Sends an error response for a failed request.
     */
    private void sendErrorResponse(MessageEnvelope requestEnvelope) {
        if (requestEnvelope.getCorrelationId() != null && requestEnvelope.getSenderId() != null) {
            try {
                Map<String, String> error = new HashMap<>();
                error.put("error", "No handler found for request type: " + requestEnvelope.getType());
                
                MessageEnvelope response = new MessageEnvelope(
                    requestEnvelope.getType() + "_response",
                    serverId,
                    requestEnvelope.getSenderId(),
                    requestEnvelope.getCorrelationId(),
                    System.currentTimeMillis(),
                    1,
                    objectMapper.valueToTree(error)
                );
                
                String serialized = serializeEnvelope(response);
                
                RedisCommands<String, String> commands = connection.sync();
                String channel = RESPONSE_CHANNEL_PREFIX + requestEnvelope.getSenderId();
                commands.publish(channel, serialized);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to send error response", e);
            }
        }
    }
    
    /**
     * Helper method to send a response to a request.
     * 
     * @param requestEnvelope The original request envelope
     * @param responsePayload The response payload
     */
    public void sendResponse(MessageEnvelope requestEnvelope, Object responsePayload) {
        if (requestEnvelope.getCorrelationId() != null && requestEnvelope.getSenderId() != null) {
            try {
                MessageEnvelope response = new MessageEnvelope(
                    requestEnvelope.getType() + "_response",
                    serverId,
                    requestEnvelope.getSenderId(),
                    requestEnvelope.getCorrelationId(),
                    System.currentTimeMillis(),
                    1,
                    objectMapper.valueToTree(responsePayload)
                );
                
                String serialized = serializeEnvelope(response);
                
                RedisCommands<String, String> commands = connection.sync();
                String channel = RESPONSE_CHANNEL_PREFIX + requestEnvelope.getSenderId();
                commands.publish(channel, serialized);
                
                logger.fine("Sent response to server: " + requestEnvelope.getSenderId() +
                          " for correlation ID: " + requestEnvelope.getCorrelationId());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to send response", e);
            }
        }
    }
    
    private MessageEnvelope createEnvelope(String type, String targetId, Object payload) {
        return new MessageEnvelope(
            type,
            serverId,
            targetId,
            UUID.randomUUID(),
            System.currentTimeMillis(),
            1,
            objectMapper.valueToTree(payload)
        );
    }
    
    private String serializeEnvelope(MessageEnvelope envelope) throws Exception {
        return objectMapper.writeValueAsString(envelope);
    }
    
    private MessageEnvelope deserializeEnvelope(String json) throws Exception {
        logger.info("[DEBUG] Attempting to deserialize message envelope from JSON: " +
                   (json.length() > 200 ? json.substring(0, 200) + "..." : json));
        try {
            MessageEnvelope envelope = objectMapper.readValue(json, MessageEnvelope.class);
            logger.info("[DEBUG] Successfully deserialized envelope with type: " + envelope.getType());
            return envelope;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[DEBUG] Failed to deserialize MessageEnvelope. Error: " + e.getMessage() +
                      "\n[DEBUG] JSON was: " + json, e);
            throw e;
        }
    }
    
    /**
     * Shuts down the message bus and releases resources.
     */
    public void shutdown() {
        running = false;
        
        try {
            // Clean up pending requests
            for (CompletableFuture<Object> future : pendingRequests.values()) {
                future.completeExceptionally(new IllegalStateException("Message bus shutting down"));
            }
            pendingRequests.clear();
            
            // Shutdown scheduler
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            
            // Close Redis connections
            if (pubSubConnection != null && pubSubConnection.isOpen()) {
                pubSubConnection.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            if (redisClient != null) {
                redisClient.shutdown();
            }
            
            // Clear handlers
            handlers.clear();
            
            logger.info("VelocityRedisMessageBus shut down");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during shutdown", e);
        }
    }
    
    /**
     * Checks if the Redis connection is available.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return connection != null && connection.isOpen() &&
               pubSubConnection != null && pubSubConnection.isOpen();
    }
    
    /**
     * Get the underlying Redis connection for advanced operations.
     * This should only be used by infrastructure components like ProxyRegistry.
     * @return The Redis connection
     */
    public StatefulRedisConnection<String, String> getRedisConnection() {
        return connection;
    }
}