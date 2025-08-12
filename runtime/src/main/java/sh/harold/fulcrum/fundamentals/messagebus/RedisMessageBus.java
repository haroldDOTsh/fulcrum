package sh.harold.fulcrum.fundamentals.messagebus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import sh.harold.fulcrum.runtime.redis.RedisConfig;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-based implementation of MessageBus using Lettuce client.
 * Provides distributed messaging across server instances.
 */
public class RedisMessageBus implements MessageBus {
    private static final Logger LOGGER = Logger.getLogger(RedisMessageBus.class.getName());
    
    private static final String BROADCAST_CHANNEL = "fulcrum:broadcast";
    private static final String SERVER_CHANNEL_PREFIX = "fulcrum:server:";
    private static final String REQUEST_CHANNEL_PREFIX = "fulcrum:request:";
    private static final String RESPONSE_CHANNEL_PREFIX = "fulcrum:response:";
    
    private final String serverId;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final ObjectMapper objectMapper;
    
    private final Map<String, List<MessageHandler>> subscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private volatile boolean running = true;
    
    public RedisMessageBus(String serverId, RedisConfig config) {
        this.serverId = serverId;
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
            
            LOGGER.info("Redis message bus initialized with server ID from ServerIdentifier: " + serverId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize Redis message bus", e);
            throw new RuntimeException("Failed to initialize Redis message bus", e);
        }
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
                    
                    // Handle regular messages
                    handleMessage(envelope);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to process message from channel: " + channel, e);
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
        
        // Subscribe to response channel for this server
        pubSubCommands.subscribe(RESPONSE_CHANNEL_PREFIX + serverId);
        
        LOGGER.info("Subscribed to Redis channels for server: " + serverId);
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        try {
            MessageEnvelope envelope = createEnvelope(type, null, payload);
            String serialized = serializeEnvelope(envelope);
            
            RedisCommands<String, String> commands = connection.sync();
            commands.publish(BROADCAST_CHANNEL, serialized);
            
            LOGGER.fine("Broadcasted message type: " + type);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to broadcast message", e);
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
            
            LOGGER.fine("Sent message type: " + type + " to server: " + targetServerId);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send message to server: " + targetServerId, e);
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
            
            LOGGER.fine("Sent request to server: " + targetServerId + " with correlation ID: " + correlationId);
        } catch (Exception e) {
            pendingRequests.remove(correlationId);
            future.completeExceptionally(e);
            LOGGER.log(Level.WARNING, "Failed to send request", e);
        }
        
        return future;
    }
    
    @Override
    public void subscribe(String type, MessageHandler handler) {
        subscriptions.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        LOGGER.fine("Subscribed handler for type: " + type);
    }
    
    @Override
    public void unsubscribe(String type, MessageHandler handler) {
        List<MessageHandler> handlers = subscriptions.get(type);
        if (handlers != null) {
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                subscriptions.remove(type);
            }
        }
        LOGGER.fine("Unsubscribed handler for type: " + type);
    }
    
    private void handleMessage(MessageEnvelope envelope) {
        List<MessageHandler> handlers = subscriptions.get(envelope.getType());
        if (handlers != null) {
            for (MessageHandler handler : handlers) {
                try {
                    handler.handle(envelope);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error handling message of type: " + envelope.getType(), e);
                }
            }
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
        return objectMapper.readValue(json, MessageEnvelope.class);
    }
    
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
            
            LOGGER.info("Redis message bus shut down");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during shutdown", e);
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