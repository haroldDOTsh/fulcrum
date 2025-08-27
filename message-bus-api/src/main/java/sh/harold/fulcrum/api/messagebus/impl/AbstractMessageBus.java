package sh.harold.fulcrum.api.messagebus.impl;

import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Abstract base class for message bus implementations.
 * Provides common stateless functionality for subscription management.
 * 
 * This class maintains no state beyond the subscription mappings
 * and delegates all platform-specific functionality to the adapter.
 */
public abstract class AbstractMessageBus implements MessageBus {
    
    protected final MessageBusAdapter adapter;
    protected final String serverId;
    protected final Logger logger;
    protected final Map<String, List<MessageHandler>> subscriptions;
    
    protected AbstractMessageBus(MessageBusAdapter adapter) {
        this.adapter = adapter;
        this.serverId = adapter.getServerId();
        this.logger = adapter.getLogger();
        this.subscriptions = new ConcurrentHashMap<>();
    }
    
    @Override
    public void subscribe(String type, MessageHandler handler) {
        subscriptions.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        logger.fine("Subscribed handler for type: " + type);
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
        logger.fine("Unsubscribed handler for type: " + type);
    }
    
    /**
     * Gets the handlers for a specific message type.
     *
     * @param type the message type
     * @return list of handlers, or null if none registered
     */
    protected List<MessageHandler> getHandlers(String type) {
        return subscriptions.get(type);
    }
    
    /**
     * Shuts down the message bus and releases resources.
     * This is a lifecycle method that should clean up any
     * resources without maintaining persistent state.
     */
    public abstract void shutdown();
}