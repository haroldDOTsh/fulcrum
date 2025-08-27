package sh.harold.fulcrum.api.messagebus.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Redis-based implementation of MessageBus using Lettuce client.
 * Provides distributed messaging across server instances.
 * 
 * This implementation is stateless beyond its connection state and
 * uses dynamic loading to avoid direct dependencies on Lettuce classes.
 */
public class RedisMessageBus extends AbstractMessageBus {
    
    private static final String BROADCAST_CHANNEL = "fulcrum:broadcast";
    private static final String SERVER_CHANNEL_PREFIX = "fulcrum:server:";
    private static final String REQUEST_CHANNEL_PREFIX = "fulcrum:request:";
    private static final String RESPONSE_CHANNEL_PREFIX = "fulcrum:response:";
    
    private final RedisConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final Map<UUID, CompletableFuture<Object>> pendingRequests;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;
    
    // Reflective access to Redis components
    private final Object redisConnection;
    private final Object pubSubConnection;
    
    public RedisMessageBus(MessageBusAdapter adapter) {
        super(adapter);
        
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        this.pendingRequests = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        try {
            // Create connection manager
            this.connectionManager = new RedisConnectionManager(adapter.getConnectionConfig());
            this.redisConnection = connectionManager.getConnection();
            this.pubSubConnection = connectionManager.getPubSubConnection();
            
            // Set up message listener (using reflection)
            setupMessageListener();
            
            // Subscribe to channels
            subscribeToChannels();
            
            adapter.onMessageBusReady();
            logger.info("RedisMessageBus initialized with server ID: " + serverId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize Redis message bus", e);
            throw new RuntimeException("Failed to initialize Redis message bus", e);
        }
    }
    
    private void setupMessageListener() {
        try {
            // Use reflection to create and add listener
            Class<?> adapterClass = Class.forName("io.lettuce.core.pubsub.RedisPubSubAdapter");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                adapterClass.getClassLoader(),
                new Class<?>[] { adapterClass },
                (proxy, method, args) -> {
                    if ("message".equals(method.getName()) && args.length == 2) {
                        String channel = (String) args[0];
                        String message = (String) args[1];
                        handleIncomingMessage(channel, message);
                    }
                    return null;
                }
            );
            
            // Add listener to pubsub connection
            pubSubConnection.getClass().getMethod("addListener", Object.class)
                .invoke(pubSubConnection, listener);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to setup message listener", e);
            throw new RuntimeException("Failed to setup message listener", e);
        }
    }
    
    private void subscribeToChannels() {
        try {
            // Get sync commands
            Object pubSubCommands = pubSubConnection.getClass().getMethod("sync")
                .invoke(pubSubConnection);
            
            // Subscribe to channels
            Class<?> commandsClass = pubSubCommands.getClass();
            commandsClass.getMethod("subscribe", String[].class)
                .invoke(pubSubCommands, new Object[] { new String[] {
                    BROADCAST_CHANNEL,
                    SERVER_CHANNEL_PREFIX + serverId,
                    REQUEST_CHANNEL_PREFIX + serverId,
                    RESPONSE_CHANNEL_PREFIX + serverId
                }});
            
            logger.info("Subscribed to Redis channels for server: " + serverId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to subscribe to channels", e);
            throw new RuntimeException("Failed to subscribe to channels", e);
        }
    }
    
    private void handleIncomingMessage(String channel, String message) {
        if (!running || !adapter.isRunning()) return;
        
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
    
    @Override
    public void broadcast(String type, Object payload) {
        try {
            MessageEnvelope envelope = createEnvelope(type, null, payload);
            String serialized = serializeEnvelope(envelope);
            
            publish(BROADCAST_CHANNEL, serialized);
            
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
            
            String channel = SERVER_CHANNEL_PREFIX + targetServerId;
            publish(channel, serialized);
            
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
            String channel = REQUEST_CHANNEL_PREFIX + targetServerId;
            publish(channel, serialized);
            
            logger.fine("Sent request to server: " + targetServerId + " with correlation ID: " + correlationId);
        } catch (Exception e) {
            pendingRequests.remove(correlationId);
            future.completeExceptionally(e);
            logger.log(Level.WARNING, "Failed to send request", e);
        }
        
        return future;
    }
    
    @Override
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
            
            // Close connections
            connectionManager.shutdown();
            
            adapter.onMessageBusShutdown();
            logger.info("RedisMessageBus shut down");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during shutdown", e);
        }
    }
    
    private void handleMessage(MessageEnvelope envelope) {
        List<MessageHandler> handlers = getHandlers(envelope.getType());
        if (handlers != null) {
            for (MessageHandler handler : handlers) {
                try {
                    handler.handle(envelope);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling message of type: " + envelope.getType(), e);
                }
            }
        }
    }
    
    private void handleRequest(MessageEnvelope envelope) {
        List<MessageHandler> handlers = getHandlers(envelope.getType());
        if (handlers != null && !handlers.isEmpty()) {
            for (MessageHandler handler : handlers) {
                try {
                    handler.handle(envelope);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling request of type: " + envelope.getType(), e);
                    sendErrorResponse(envelope);
                }
            }
        } else {
            sendErrorResponse(envelope);
        }
    }
    
    private void handleResponse(MessageEnvelope envelope) {
        UUID correlationId = envelope.getCorrelationId();
        if (correlationId != null) {
            CompletableFuture<Object> future = pendingRequests.remove(correlationId);
            if (future != null) {
                try {
                    Object response = objectMapper.treeToValue(envelope.getPayload(), Object.class);
                    future.complete(response);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        }
    }
    
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
                String channel = RESPONSE_CHANNEL_PREFIX + requestEnvelope.getSenderId();
                publish(channel, serialized);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to send error response", e);
            }
        }
    }
    
    private void publish(String channel, String message) {
        try {
            // Get sync commands from connection using reflection
            Object commands = redisConnection.getClass().getMethod("sync")
                .invoke(redisConnection);
            
            // Publish message
            commands.getClass().getMethod("publish", String.class, String.class)
                .invoke(commands, channel, message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish message", e);
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
    
    /**
     * Checks if the Redis connection is available.
     */
    public boolean isConnected() {
        return connectionManager != null && connectionManager.isConnected();
    }
}