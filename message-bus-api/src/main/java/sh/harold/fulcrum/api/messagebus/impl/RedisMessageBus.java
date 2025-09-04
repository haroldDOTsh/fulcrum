package sh.harold.fulcrum.api.messagebus.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
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
    
    // Message TTL in seconds (30 seconds for registration messages)
    private static final long REGISTRATION_MESSAGE_TTL_SECONDS = 30;
    private static final long DEFAULT_MESSAGE_TTL_SECONDS = 60;
    
    // Cache key prefixes for TTL tracking
    private static final String MESSAGE_CACHE_PREFIX = "fulcrum:msg:";
    private static final String MESSAGE_ID_PREFIX = "fulcrum:msgid:";
    
    private final RedisConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final Map<UUID, CompletableFuture<Object>> pendingRequests;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;
    
    // Redis connections
    private final StatefulRedisConnection<String, String> redisConnection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    
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
            
            // Clean up any stale messages from previous runs
            cleanupStaleMessages();
            
            adapter.onMessageBusReady();
            logger.info("RedisMessageBus initialized with server ID: " + serverId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize Redis message bus", e);
            throw new RuntimeException("Failed to initialize Redis message bus", e);
        }
    }
    
    private void setupMessageListener() {
        // Create a simple adapter that extends RedisPubSubAdapter
        RedisPubSubAdapter<String, String> listener = new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                handleIncomingMessage(channel, message);
            }

            @Override
            public void message(String pattern, String channel, String message) {
                handleIncomingMessage(channel, message);
            }
        };
        
        // Add the listener to the pub/sub connection
        pubSubConnection.addListener(listener);
        
        logger.fine("Redis message listener setup completed");
    }
    
    private void subscribeToChannels() {
        // Get sync commands from the pub/sub connection
        RedisPubSubCommands<String, String> syncCommands = pubSubConnection.sync();
        
        // Subscribe to default channels
        syncCommands.subscribe(
            BROADCAST_CHANNEL,
            SERVER_CHANNEL_PREFIX + serverId,
            REQUEST_CHANNEL_PREFIX + serverId,
            RESPONSE_CHANNEL_PREFIX + serverId
        );
        
        logger.info("Subscribed to Redis channels for server: " + serverId);
    }
    
    /**
     * Subscribe to additional channels dynamically
     * This allows services to listen to specific message type channels
     */
    private void subscribeToTypeChannel(String type) {
        try {
            RedisPubSubCommands<String, String> syncCommands = pubSubConnection.sync();
            String typeChannel = "fulcrum:" + type;
            
            // Subscribe to the specific type channel
            syncCommands.subscribe(typeChannel);
            
            logger.fine("Subscribed to Redis channel: " + typeChannel + " for type: " + type);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to subscribe to type channel: " + type, e);
        }
    }
    
    @Override
    public void subscribe(String type, MessageHandler handler) {
        // CRITICAL: Must call super to register the handler in the subscriptions map
        super.subscribe(type, handler);
        
        // CRITICAL FIX: Also subscribe to the Redis channel for this type
        // This allows the service to receive messages broadcast to specific types
        subscribeToTypeChannel(type);
        
        logger.info("Subscribed to type '" + type + "' with handler and Redis channel 'fulcrum:" + type + "'");
    }
    
    private void handleIncomingMessage(String channel, String message) {
        if (!running || !adapter.isRunning()) {
            return;
        }
        
        try {
            logger.info("[REDIS-DEBUG] Received message on channel: " + channel);
            logger.info("[REDIS-DEBUG] Raw message: " + message);
            
            MessageEnvelope envelope = deserializeEnvelope(message);
            logger.info("[REDIS-DEBUG] Deserialized envelope type: " + envelope.getType() + ", sender: " + envelope.getSenderId());
            
            // CRITICAL FIX: Registration response messages should NEVER skip duplicate check
            // These come through type-based subscription channels and need to be handled
            boolean isRegistrationResponse = envelope.getType() != null &&
                                            (envelope.getType().contains("registration:response") ||
                                             envelope.getType().contains("registration-response"));
            
            // Skip duplicate check for registration responses and other type-based messages
            if (!isRegistrationResponse) {
                // Only check for duplicates on direct server-to-server messages
                boolean isDirectMessage = channel.equals(SERVER_CHANNEL_PREFIX + serverId) ||
                                        channel.equals(REQUEST_CHANNEL_PREFIX + serverId) ||
                                        channel.equals(RESPONSE_CHANNEL_PREFIX + serverId);
                
                if (isDirectMessage && isDuplicateMessage(envelope)) {
                    logger.info("[REDIS-DEBUG] Skipping duplicate direct message on channel: " + channel);
                    return;
                }
            } else {
                logger.info("[REDIS-DEBUG] Registration response - bypassing duplicate check");
            }
            
            // Handle response messages
            if (channel.startsWith(RESPONSE_CHANNEL_PREFIX)) {
                logger.info("[REDIS-DEBUG] Handling as RESPONSE message");
                handleResponse(envelope);
                return;
            }
            
            // Handle request messages
            if (channel.startsWith(REQUEST_CHANNEL_PREFIX)) {
                logger.info("[REDIS-DEBUG] Handling as REQUEST message");
                handleRequest(envelope);
                return;
            }
            
            // Handle regular messages (including type-based broadcasts)
            logger.info("[REDIS-DEBUG] Handling as REGULAR message - will invoke handlers for type: " + envelope.getType());
            handleMessage(envelope);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling incoming message on channel " + channel, e);
        }
    }
    
    /**
     * Check if we've already processed this message
     * Uses Redis to track processed message IDs with TTL
     */
    private boolean isDuplicateMessage(MessageEnvelope envelope) {
        if (envelope.getCorrelationId() == null) {
            return false; // Can't deduplicate without correlation ID
        }
        
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            String messageKey = MESSAGE_ID_PREFIX + envelope.getCorrelationId();
            
            // Check if message ID exists (already processed)
            String existing = commands.get(messageKey);
            if (existing != null) {
                return true; // Already processed
            }
            
            // Mark as processed with TTL
            commands.setex(messageKey, DEFAULT_MESSAGE_TTL_SECONDS, "1");
            return false;
        } catch (Exception e) {
            logger.log(Level.FINE, "Error checking duplicate message", e);
            return false; // Assume not duplicate on error
        }
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        try {
            MessageEnvelope envelope = createEnvelope(type, null, payload);
            String serialized = serializeEnvelope(envelope);
            
            // Store message with TTL if it's a registration message
            if (isRegistrationMessage(type)) {
                storeMessageWithTTL(envelope, serialized, REGISTRATION_MESSAGE_TTL_SECONDS);
            }
            
            // CRITICAL: Only publish to the specific type channel, not broadcast
            // This prevents duplicate message issues where the same message arrives on multiple channels
            String typeChannel = "fulcrum:" + type;
            publish(typeChannel, serialized);
            
            logger.fine("Broadcasted message type: " + type + " to channel: " + typeChannel);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to broadcast message", e);
        }
    }
    
    @Override
    public void send(String targetServerId, String type, Object payload) {
        try {
            MessageEnvelope envelope = createEnvelope(type, targetServerId, payload);
            String serialized = serializeEnvelope(envelope);
            
            // Store message with TTL if it's a registration message
            if (isRegistrationMessage(type)) {
                storeMessageWithTTL(envelope, serialized, REGISTRATION_MESSAGE_TTL_SECONDS);
            }
            
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
        logger.info("[REDIS-DEBUG] handleMessage called for type: " + envelope.getType());
        List<MessageHandler> handlers = getHandlers(envelope.getType());
        
        if (handlers != null && !handlers.isEmpty()) {
            logger.info("[REDIS-DEBUG] Found " + handlers.size() + " handlers for type: " + envelope.getType());
            // Check if the payload needs deserialization
            Object deserializedPayload = deserializePayload(envelope);
            
            // Create a new envelope with the deserialized payload
            // The payload is converted back to JsonNode for the envelope
            MessageEnvelope processedEnvelope = new MessageEnvelope(
                envelope.getType(),
                envelope.getSenderId(),
                envelope.getTargetId(),
                envelope.getCorrelationId(),
                envelope.getTimestamp(),
                envelope.getVersion(),
                objectMapper.valueToTree(deserializedPayload)  // Convert to JsonNode
            );
            
            // Store the deserialized object in the headers for easy access by handlers
            // This way handlers can get the typed object without re-deserializing
            processedEnvelope = new MessageEnvelope(
                processedEnvelope.getType(),
                processedEnvelope.getSenderId(),
                processedEnvelope.getTargetId(),
                processedEnvelope.getCorrelationId(),
                processedEnvelope.getTimestamp(),
                processedEnvelope.getVersion(),
                objectMapper.valueToTree(deserializedPayload)
            );
            
            for (MessageHandler handler : handlers) {
                try {
                    logger.info("[REDIS-DEBUG] Invoking handler: " + handler.getClass().getName());
                    handler.handle(processedEnvelope);
                    logger.info("[REDIS-DEBUG] Handler invoked successfully");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling message of type: " + envelope.getType(), e);
                }
            }
        } else {
            logger.info("[REDIS-DEBUG] No handlers found for type: " + envelope.getType());
            logger.info("[REDIS-DEBUG] Available subscriptions: " + subscriptions.keySet());
        }
    }
    
    private void handleRequest(MessageEnvelope envelope) {
        List<MessageHandler> handlers = getHandlers(envelope.getType());
        if (handlers != null && !handlers.isEmpty()) {
            // Deserialize the payload to the appropriate type
            Object deserializedPayload = deserializePayload(envelope);
            
            // Create a new envelope with the deserialized payload
            // The payload is converted back to JsonNode for the envelope
            MessageEnvelope processedEnvelope = new MessageEnvelope(
                envelope.getType(),
                envelope.getSenderId(),
                envelope.getTargetId(),
                envelope.getCorrelationId(),
                envelope.getTimestamp(),
                envelope.getVersion(),
                objectMapper.valueToTree(deserializedPayload)  // Convert to JsonNode
            );
            
            for (MessageHandler handler : handlers) {
                try {
                    handler.handle(processedEnvelope);
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
        // Get sync commands from the regular connection
        RedisCommands<String, String> commands = redisConnection.sync();
        
        // Publish message
        commands.publish(channel, message);
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
     * Check if a message type is a registration-related message
     */
    private boolean isRegistrationMessage(String type) {
        return type != null && (
            type.contains("registration") ||
            type.contains("register") ||
            type.equals("proxy:registration:response") ||
            type.equals("server:registration:response") ||
            type.equals("registry:register")
        );
    }
    
    /**
     * Store a message in Redis with TTL for deduplication and cleanup
     */
    private void storeMessageWithTTL(MessageEnvelope envelope, String serialized, long ttlSeconds) {
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            
            // Store the actual message content with TTL
            String messageKey = MESSAGE_CACHE_PREFIX + envelope.getCorrelationId();
            commands.setex(messageKey, ttlSeconds, serialized);
            
            // Also store just the ID for deduplication with same TTL
            String idKey = MESSAGE_ID_PREFIX + envelope.getCorrelationId();
            commands.setex(idKey, ttlSeconds, "1");
            
            logger.fine("Stored message with TTL: " + ttlSeconds + "s for correlation ID: " + envelope.getCorrelationId());
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to store message with TTL", e);
        }
    }
    
    /**
     * Clean up stale messages on startup
     * This clears old registration messages that might be stuck in Redis
     */
    public void cleanupStaleMessages() {
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            
            // Use simpler approach - scan and delete keys with pattern
            // This avoids the ScriptOutputType issue
            Set<String> keysToDelete = new HashSet<>();
            
            // Scan for message cache keys
            commands.keys(MESSAGE_CACHE_PREFIX + "*").forEach(keysToDelete::add);
            
            // Scan for message ID keys
            commands.keys(MESSAGE_ID_PREFIX + "*").forEach(keysToDelete::add);
            
            // Delete in batches
            if (!keysToDelete.isEmpty()) {
                for (String key : keysToDelete) {
                    commands.del(key);
                }
                logger.info("Cleaned up " + keysToDelete.size() + " stale message keys from Redis");
            } else {
                logger.info("No stale messages to clean up");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to cleanup stale messages", e);
        }
    }
    
    /**
     * Checks if the Redis connection is available.
     */
    public boolean isConnected() {
        return connectionManager != null && connectionManager.isConnected();
    }
}