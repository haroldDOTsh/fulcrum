package sh.harold.fulcrum.api.messagebus.impl;

import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;

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
    protected final Map<String, Class<?>> typeRegistry;
    protected final ObjectMapper objectMapper;
    
    protected AbstractMessageBus(MessageBusAdapter adapter) {
        this.adapter = adapter;
        this.serverId = adapter.getServerId();
        this.logger = adapter.getLogger();
        this.subscriptions = new ConcurrentHashMap<>();
        this.typeRegistry = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // Register common message types
        registerMessageTypes();
    }
    
    /**
     * Register common message types for proper deserialization
     */
    private void registerMessageTypes() {
        // Register all common message types from the message-bus-api
        typeRegistry.put("registry:register", sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest.class);
        typeRegistry.put("server:heartbeat", sh.harold.fulcrum.api.messagebus.messages.ServerHeartbeatMessage.class);
        typeRegistry.put("server:evacuation", sh.harold.fulcrum.api.messagebus.messages.ServerEvacuationRequest.class);
        typeRegistry.put("registry:server:remove", sh.harold.fulcrum.api.messagebus.messages.ServerRemovalNotification.class);
        // CRITICAL: Register ServerRemovalNotification for registry:proxy channel
        typeRegistry.put("registry:proxy", sh.harold.fulcrum.api.messagebus.messages.ServerRemovalNotification.class);
        typeRegistry.put("proxy:registration:response", Map.class);
        typeRegistry.put("server:registration:response", Map.class);
        typeRegistry.put("registry:server:added", Map.class);
        typeRegistry.put("registry:server:removed", Map.class);
        typeRegistry.put("registry:proxy:unavailable", Map.class);
        typeRegistry.put("registry:status:change", Map.class);
        typeRegistry.put("proxy:unregister", Map.class);
        typeRegistry.put("server:evacuation:response", Map.class);
    }
    
    /**
     * Deserialize the payload from a MessageEnvelope to the appropriate type
     */
    protected Object deserializePayload(MessageEnvelope envelope) {
        try {
            JsonNode payload = envelope.getPayload();
            if (payload != null) {
                String messageType = envelope.getType();
                Class<?> targetClass = typeRegistry.get(messageType);
                
                if (targetClass != null) {
                    // Deserialize the JsonNode to the target class
                    return objectMapper.treeToValue(payload, targetClass);
                } else {
                    // If no type registered, try to convert to Map as fallback
                    return objectMapper.treeToValue(payload, Map.class);
                }
            }
            
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to deserialize payload for type: " + envelope.getType(), e);
            // Return the JsonNode if deserialization fails
            return envelope.getPayload();
        }
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