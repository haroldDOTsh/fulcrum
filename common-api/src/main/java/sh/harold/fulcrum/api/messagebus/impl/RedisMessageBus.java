package sh.harold.fulcrum.api.messagebus.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
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
 * <p>
 * This implementation is stateless beyond its connection state and
 * uses dynamic loading to avoid direct dependencies on Lettuce classes.
 * <p>
 * Supports both legacy channel names and new standardized channel names
 * for backward compatibility during migration.
 */
public class RedisMessageBus extends AbstractMessageBus {

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
    // Redis connections
    private final StatefulRedisConnection<String, String> redisConnection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private volatile boolean running = true;
    private volatile String subscribedServerId;
    private final Set<String> pendingSubscriptionSummary = ConcurrentHashMap.newKeySet();
    private final Object subscriptionSummaryLock = new Object();
    private volatile ScheduledFuture<?> pendingSubscriptionSummaryTask;

    public RedisMessageBus(MessageBusAdapter adapter) {
        super(adapter);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
            logger.info("RedisMessageBus initialized with server ID: " + adapter.getServerId());
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
        // Use async commands to prevent blocking during initial subscription
        var asyncCommands = pubSubConnection.async();

        // Subscribe to standardized channels only
        String currentServerId = adapter.getServerId();

        // Subscribe asynchronously to prevent timeout issues
        asyncCommands.subscribe(
                ChannelConstants.BROADCAST_CHANNEL,
                ChannelConstants.getServerDirectChannel(currentServerId),
                ChannelConstants.getRequestChannel(currentServerId),
                ChannelConstants.getResponseChannel(currentServerId)
        ).thenAccept(result -> {
            logger.info("Subscribed to standardized Redis channels for server: " + currentServerId);
            subscribedServerId = currentServerId;
        }).exceptionally(throwable -> {
            logger.log(Level.WARNING, "Failed to subscribe to initial channels", throwable);
            // Fallback to sync subscription on error
            try {
                RedisPubSubCommands<String, String> syncCommands = pubSubConnection.sync();
                syncCommands.subscribe(
                        ChannelConstants.BROADCAST_CHANNEL,
                        ChannelConstants.getServerDirectChannel(currentServerId),
                        ChannelConstants.getRequestChannel(currentServerId),
                        ChannelConstants.getResponseChannel(currentServerId)
                );
                logger.info("Fallback: Subscribed to standardized Redis channels for server: " + currentServerId);
                subscribedServerId = currentServerId;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to subscribe to channels even with fallback", e);
            }
            return null;
        });
    }

    /**
     * Subscribe to additional channels dynamically
     * This allows services to listen to specific message type channels
     */
    private void subscribeToTypeChannel(String type) {
        try {
            // Use async commands to prevent blocking
            var asyncCommands = pubSubConnection.async();

            // Use the standardized channel format directly
            String standardChannel = type.startsWith("fulcrum.") ? type : "fulcrum.custom." + type;

            // Subscribe to the standardized channel asynchronously
            asyncCommands.subscribe(standardChannel).thenAccept(result -> {
                logger.fine("Subscribed to channel: " + standardChannel + " for type: " + type);
            }).exceptionally(throwable -> {
                logger.log(Level.WARNING, "Failed to subscribe to type channel: " + type, throwable);
                return null;
            });
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

        queueSubscriptionSummary(type);
    }

    @Override
    public synchronized void refreshServerIdentity() {
        if (!running || !adapter.isRunning()) {
            return;
        }

        String newServerId = adapter.getServerId();
        String oldServerId = subscribedServerId;

        if (Objects.equals(newServerId, oldServerId) || newServerId == null || newServerId.isBlank()) {
            return;
        }

        try {
            // Use async commands so we don't block the Redis pub/sub event loop thread.
            var asyncCommands = pubSubConnection.async();

            if (oldServerId != null) {
                asyncCommands.unsubscribe(
                        ChannelConstants.getServerDirectChannel(oldServerId),
                        ChannelConstants.getRequestChannel(oldServerId),
                        ChannelConstants.getResponseChannel(oldServerId)
                ).whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        logger.log(Level.WARNING, "Failed to unsubscribe from identity channels for server: " + oldServerId, throwable);
                    } else {
                        logger.info("RedisMessageBus unsubscribed from identity channels for server: " + oldServerId);
                    }
                });
            }

            asyncCommands.subscribe(
                    ChannelConstants.getServerDirectChannel(newServerId),
                    ChannelConstants.getRequestChannel(newServerId),
                    ChannelConstants.getResponseChannel(newServerId)
            ).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    logger.log(Level.SEVERE, "Failed to subscribe to identity channels for server: " + newServerId, throwable);
                    return;
                }

                subscribedServerId = newServerId;
                logger.info("RedisMessageBus updated server ID: " + oldServerId + " -> " + newServerId);
            });
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Failed to refresh Redis channel subscriptions for new server ID", ex);
        }
    }

    private void handleIncomingMessage(String channel, String message) {
        if (!running || !adapter.isRunning()) {
            return;
        }

        try {
            MessageEnvelope envelope = deserializeEnvelope(message);

            // CRITICAL FIX: Registration response messages should NEVER skip duplicate check
            // These come through type-based subscription channels and need to be handled
            boolean isRegistrationResponse = envelope.type() != null &&
                    (envelope.type().contains("registration:response") ||
                            envelope.type().contains("registration-response"));

            // Skip duplicate check for registration responses and other type-based messages
            if (!isRegistrationResponse) {
                // Only check for duplicates on direct server-to-server messages
                String currentServerId = adapter.getServerId();
                boolean isDirectMessage =
                        channel.equals(ChannelConstants.getServerDirectChannel(currentServerId)) ||
                                channel.equals(ChannelConstants.getRequestChannel(currentServerId)) ||
                                channel.equals(ChannelConstants.getResponseChannel(currentServerId));

                if (isDirectMessage && isDuplicateMessage(envelope)) {
                    return;
                }
            }

            // Handle response messages (standardized format only)
            if (channel.startsWith(ChannelConstants.RESPONSE_PREFIX)) {
                handleResponse(envelope);
                return;
            }

            // Handle request messages (standardized format only)
            if (channel.startsWith(ChannelConstants.REQUEST_PREFIX)) {
                handleRequest(envelope);
                return;
            }

            // Handle regular messages (including type-based broadcasts)
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
        if (envelope.correlationId() == null) {
            return false; // Can't deduplicate without correlation ID
        }

        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            String messageKey = MESSAGE_ID_PREFIX + envelope.correlationId();

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

            // Use the standardized channel for this type
            String standardChannel = type.startsWith("fulcrum.") ? type : "fulcrum.custom." + type;
            publish(standardChannel, serialized);

            logger.fine("Broadcasted message type: " + type + " to channel: " + standardChannel);
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

            // Use standardized channel
            String channel = ChannelConstants.getServerDirectChannel(targetServerId);
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
                    adapter.getServerId(),
                    targetServerId,
                    correlationId,
                    System.currentTimeMillis(),
                    1,
                    objectMapper.valueToTree(payload)
            );

            String serialized = serializeEnvelope(envelope);
            String channel = ChannelConstants.getRequestChannel(targetServerId);
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
            synchronized (subscriptionSummaryLock) {
                if (pendingSubscriptionSummaryTask != null) {
                    pendingSubscriptionSummaryTask.cancel(false);
                    pendingSubscriptionSummaryTask = null;
                }
            }
            pendingSubscriptionSummary.clear();

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

    private void queueSubscriptionSummary(String type) {
        if (!running || scheduler.isShutdown()) {
            return;
        }

        pendingSubscriptionSummary.add(type);

        synchronized (subscriptionSummaryLock) {
            if (pendingSubscriptionSummaryTask != null) {
                pendingSubscriptionSummaryTask.cancel(false);
            }
            pendingSubscriptionSummaryTask = scheduler.schedule(this::logSubscriptionSummary, 200, TimeUnit.MILLISECONDS);
        }
    }

    private void logSubscriptionSummary() {
        if (!running) {
            pendingSubscriptionSummary.clear();
            return;
        }

        List<String> snapshot = new ArrayList<>(pendingSubscriptionSummary);
        pendingSubscriptionSummary.clear();

        int totalChannels = subscriptions.size();
        if (totalChannels == 0) {
            logger.info("No message bus listeners registered");
        } else if (snapshot.isEmpty()) {
            logger.info("Registered listeners on " + totalChannels + " channels");
        } else {
            snapshot.sort(String::compareToIgnoreCase);
            String summary = summarizeChannels(snapshot);
            logger.info("Registered listeners on " + totalChannels + " channels (added: " + summary + ")");
        }

        synchronized (subscriptionSummaryLock) {
            pendingSubscriptionSummaryTask = null;
        }
    }

    private String summarizeChannels(List<String> channels) {
        int maxEntries = 5;
        if (channels.size() <= maxEntries) {
            return String.join(", ", channels);
        }
        List<String> head = channels.subList(0, maxEntries);
        int remaining = channels.size() - maxEntries;
        return String.join(", ", head) + " +" + remaining + " more";
    }

    private void handleMessage(MessageEnvelope envelope) {
        // First process typed handlers
        processTypedHandlers(envelope);

        // Then process regular handlers
        List<MessageHandler> handlers = getHandlers(envelope.type());

        if (handlers != null && !handlers.isEmpty()) {
            // Check if the payload needs deserialization
            Object deserializedPayload = deserializePayload(envelope);

            // Create a new envelope with the deserialized payload
            // The payload is converted back to JsonNode for the envelope
            MessageEnvelope processedEnvelope = new MessageEnvelope(
                    envelope.type(),
                    envelope.senderId(),
                    envelope.targetId(),
                    envelope.correlationId(),
                    envelope.timestamp(),
                    envelope.version(),
                    objectMapper.valueToTree(deserializedPayload)  // Convert to JsonNode
            );

            // Store the deserialized object in the headers for easy access by handlers
            // This way handlers can get the typed object without re-deserializing
            processedEnvelope = new MessageEnvelope(
                    processedEnvelope.type(),
                    processedEnvelope.senderId(),
                    processedEnvelope.targetId(),
                    processedEnvelope.correlationId(),
                    processedEnvelope.timestamp(),
                    processedEnvelope.version(),
                    objectMapper.valueToTree(deserializedPayload)
            );

            for (MessageHandler handler : handlers) {
                try {
                    handler.handle(processedEnvelope);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling message of type: " + envelope.type(), e);
                }
            }
        }
    }

    private void handleRequest(MessageEnvelope envelope) {
        List<MessageHandler> handlers = getHandlers(envelope.type());
        if (handlers != null && !handlers.isEmpty()) {
            // Deserialize the payload to the appropriate type
            Object deserializedPayload = deserializePayload(envelope);

            // Create a new envelope with the deserialized payload
            // The payload is converted back to JsonNode for the envelope
            MessageEnvelope processedEnvelope = new MessageEnvelope(
                    envelope.type(),
                    envelope.senderId(),
                    envelope.targetId(),
                    envelope.correlationId(),
                    envelope.timestamp(),
                    envelope.version(),
                    objectMapper.valueToTree(deserializedPayload)  // Convert to JsonNode
            );

            for (MessageHandler handler : handlers) {
                try {
                    handler.handle(processedEnvelope);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error handling request of type: " + envelope.type(), e);
                    sendErrorResponse(envelope);
                }
            }
        } else {
            sendErrorResponse(envelope);
        }
    }

    private void handleResponse(MessageEnvelope envelope) {
        UUID correlationId = envelope.correlationId();
        if (correlationId != null) {
            CompletableFuture<Object> future = pendingRequests.remove(correlationId);
            if (future != null) {
                try {
                    Object response = objectMapper.treeToValue(envelope.payload(), Object.class);
                    future.complete(response);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        }
    }

    private void sendErrorResponse(MessageEnvelope requestEnvelope) {
        if (requestEnvelope.correlationId() != null && requestEnvelope.senderId() != null) {
            try {
                Map<String, String> error = new HashMap<>();
                error.put("error", "No handler found for request type: " + requestEnvelope.type());

                MessageEnvelope response = new MessageEnvelope(
                        requestEnvelope.type() + "_response",
                        adapter.getServerId(),
                        requestEnvelope.senderId(),
                        requestEnvelope.correlationId(),
                        System.currentTimeMillis(),
                        1,
                        objectMapper.valueToTree(error)
                );

                String serialized = serializeEnvelope(response);
                String channel = ChannelConstants.getResponseChannel(requestEnvelope.senderId());
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
                adapter.getServerId(),  // CRITICAL: Use dynamic ID from adapter instead of cached value
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
            String messageKey = MESSAGE_CACHE_PREFIX + envelope.correlationId();
            commands.setex(messageKey, ttlSeconds, serialized);

            // Also store just the ID for deduplication with same TTL
            String idKey = MESSAGE_ID_PREFIX + envelope.correlationId();
            commands.setex(idKey, ttlSeconds, "1");

            logger.fine("Stored message with TTL: " + ttlSeconds + "s for correlation ID: " + envelope.correlationId());
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
     * Publish an envelope to the message bus.
     * Implementation of abstract method from AbstractMessageBus.
     *
     * @param envelope the message envelope to publish
     */
    @Override
    protected void publishEnvelope(MessageEnvelope envelope) {
        try {
            String serialized = serializeEnvelope(envelope);

            // Determine the channel based on the envelope
            String channel;
            if (envelope.targetId() != null) {
                // Direct message to specific server
                channel = ChannelConstants.getServerDirectChannel(envelope.targetId());
            } else {
                // Broadcast message
                String type = envelope.type();
                channel = type.startsWith("fulcrum.") ? type : "fulcrum.custom." + type;
            }

            // Store message with TTL if it's a registration message
            if (isRegistrationMessage(envelope.type())) {
                storeMessageWithTTL(envelope, serialized, REGISTRATION_MESSAGE_TTL_SECONDS);
            }

            publish(channel, serialized);

            logger.fine("Published envelope type: " + envelope.type() + " to channel: " + channel);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to publish envelope", e);
            throw new RuntimeException("Failed to publish envelope", e);
        }
    }

    /**
     * Checks if the Redis connection is available.
     */
    public boolean isConnected() {
        return connectionManager != null && connectionManager.isConnected();
    }

    /**
     * Subscribe to a channel asynchronously without blocking.
     * This is useful for subscribing to channels after server registration
     * without causing heartbeat delays.
     *
     * @param channel the channel to subscribe to
     * @param handler the message handler for this channel
     */
    public void subscribeAsync(String channel, MessageHandler handler) {
        // Store the handler first
        super.subscribe(channel, handler);

        // Then subscribe to the Redis channel asynchronously
        try {
            var asyncCommands = pubSubConnection.async();

            String standardChannel = channel.startsWith("fulcrum.") ? channel : "fulcrum.custom." + channel;

            asyncCommands.subscribe(standardChannel).thenAccept(result -> {
                logger.fine("Async subscribed to channel: " + standardChannel);
            }).exceptionally(throwable -> {
                logger.log(Level.WARNING, "Failed to async subscribe to channel: " + channel, throwable);
                return null;
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during async subscription to channel: " + channel, e);
        }
    }
}
