package sh.harold.fulcrum.api.messagebus.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Simple in-memory implementation of MessageBus.
 * Used for single-server setups or when Redis is not available.
 * 
 * This implementation is completely stateless and only handles
 * local message delivery within the current process.
 */
public class InMemoryMessageBus extends AbstractMessageBus {
    
    private final ObjectMapper objectMapper;
    
    public InMemoryMessageBus(MessageBusAdapter adapter) {
        super(adapter);
        this.objectMapper = new ObjectMapper();
        adapter.onMessageBusReady();
        logger.info("InMemoryMessageBus initialized with server ID: " + serverId);
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        logger.fine("Broadcasting message type: " + type + " (local only)");
        
        // Create envelope for local delivery
        MessageEnvelope envelope = createEnvelope(type, null, payload);
        
        // Deliver locally
        deliverMessage(envelope);
    }
    
    @Override
    public void send(String targetServerId, String type, Object payload) {
        logger.fine("Sending message type: " + type + " to server: " + targetServerId + " (local only)");
        
        // In in-memory mode, only deliver if target is this server
        if (serverId.equals(targetServerId)) {
            MessageEnvelope envelope = createEnvelope(type, targetServerId, payload);
            deliverMessage(envelope);
        }
    }
    
    @Override
    public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
        logger.fine("Requesting from server: " + targetServerId + " with type: " + type + " (local only)");
        
        // In in-memory mode, we can only handle requests to ourselves
        if (!serverId.equals(targetServerId)) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException(
                "InMemoryMessageBus cannot send requests to remote servers"));
            return future;
        }
        
        // For local requests, just return a completed future with null
        // Real request-response handling would require more infrastructure
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public void shutdown() {
        subscriptions.clear();
        adapter.onMessageBusShutdown();
        logger.info("InMemoryMessageBus shut down");
    }
    
    /**
     * Creates a message envelope.
     */
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
    
    /**
     * Delivers a message to local handlers.
     * This is a synchronous operation executed in the caller's thread.
     */
    private void deliverMessage(MessageEnvelope envelope) {
        List<MessageHandler> handlers = getHandlers(envelope.getType());
        if (handlers != null) {
            for (MessageHandler handler : handlers) {
                try {
                    handler.handle(envelope);
                } catch (Exception e) {
                    logger.warning("Error handling message of type " + envelope.getType() + ": " + e.getMessage());
                }
            }
        }
    }
}