package sh.harold.fulcrum.fundamentals.messagebus;

import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Simple in-memory implementation of MessageBus for basic functionality.
 * This can be replaced with RedisMessageBus when Redis is properly configured.
 */
public class SimpleMessageBus implements MessageBus {
    private static final Logger LOGGER = Logger.getLogger(SimpleMessageBus.class.getName());
    
    private final String serverId;
    private final Map<String, List<MessageHandler>> subscriptions = new ConcurrentHashMap<>();
    
    public SimpleMessageBus(String serverId) {
        this.serverId = serverId;
    }
    
    @Override
    public void broadcast(String type, Object payload) {
        LOGGER.info("Broadcasting message type: " + type);
        // In a simple implementation, just handle locally
        List<MessageHandler> handlers = subscriptions.get(type);
        if (handlers != null) {
            for (MessageHandler handler : handlers) {
                try {
                    // We would need to create an envelope here, but for simplicity
                    // we're just logging
                    LOGGER.fine("Would handle broadcast message of type: " + type);
                } catch (Exception e) {
                    LOGGER.warning("Error handling message: " + e.getMessage());
                }
            }
        }
    }
    
    @Override
    public void send(String targetServerId, String type, Object payload) {
        LOGGER.info("Sending message type: " + type + " to server: " + targetServerId);
        // Simple implementation - just log
    }
    
    @Override
    public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
        LOGGER.info("Requesting from server: " + targetServerId + " with type: " + type + " (timeout: " + timeout + ")");
        // Simple implementation - return empty future
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public void subscribe(String type, MessageHandler handler) {
        subscriptions.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        LOGGER.info("Subscribed handler for type: " + type);
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
        LOGGER.info("Unsubscribed handler for type: " + type);
    }
    
    public void shutdown() {
        subscriptions.clear();
        LOGGER.info("Message bus shut down");
    }
}