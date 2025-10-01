package sh.harold.fulcrum.api.messagebus.impl;

import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.BaseMessage;
import sh.harold.fulcrum.api.messagebus.TypedMessageHandler;
import sh.harold.fulcrum.api.messagebus.MessageTypeRegistry;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    protected final Logger logger;
    protected final Map<String, List<MessageHandler>> subscriptions;
    protected final Map<String, List<TypedMessageHandler<?>>> typedSubscriptions;
    protected final Map<String, Class<?>> typeRegistry;
    protected final ObjectMapper objectMapper;
    protected final MessageTypeRegistry messageTypeRegistry;
    
    protected AbstractMessageBus(MessageBusAdapter adapter) {
        this.adapter = adapter;
        this.logger = adapter.getLogger();
        this.subscriptions = new ConcurrentHashMap<>();
        this.typedSubscriptions = new ConcurrentHashMap<>();
        this.typeRegistry = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.messageTypeRegistry = MessageTypeRegistry.getInstance();
        
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
                
                // First try the new type registry for BaseMessage types
                if (messageTypeRegistry.isTypeRegistered(messageType)) {
                    try {
                        return messageTypeRegistry.deserialize(messageType, payload);
                    } catch (Exception e) {
                        logger.log(Level.FINE, "Failed to deserialize with MessageTypeRegistry, falling back to legacy", e);
                    }
                }
                
                // Fall back to legacy type registry
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
    
    /**
     * Get the current server ID from the adapter.
     * This method should be used instead of caching the server ID,
     * to support dynamic ID updates (e.g., when proxy receives permanent ID).
     */
    protected String getServerId() {
        return adapter.getServerId();
    }

    @Override
    public String currentServerId() {
        return adapter.getServerId();
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
     * Subscribe to messages of a specific type with a typed handler.
     *
     * @param messageClass the class of messages to handle
     * @param handler the typed handler for the messages
     * @param <T> the message type
     */
    public <T extends BaseMessage> void subscribe(Class<T> messageClass, TypedMessageHandler<T> handler) {
        String messageType = messageTypeRegistry.getTypeForClass(messageClass);
        if (messageType == null) {
            throw new IllegalArgumentException("Message class not registered: " + messageClass.getName());
        }
        
        typedSubscriptions.computeIfAbsent(messageType, k -> new CopyOnWriteArrayList<>()).add(handler);
        logger.fine("Subscribed typed handler for type: " + messageType);
    }
    
    /**
     * Unsubscribe a typed handler from messages.
     *
     * @param messageClass the class of messages
     * @param handler the typed handler to remove
     * @param <T> the message type
     */
    public <T extends BaseMessage> void unsubscribe(Class<T> messageClass, TypedMessageHandler<T> handler) {
        String messageType = messageTypeRegistry.getTypeForClass(messageClass);
        if (messageType != null) {
            List<TypedMessageHandler<?>> handlers = typedSubscriptions.get(messageType);
            if (handlers != null) {
                handlers.remove(handler);
                if (handlers.isEmpty()) {
                    typedSubscriptions.remove(messageType);
                }
            }
            logger.fine("Unsubscribed typed handler for type: " + messageType);
        }
    }
    
    /**
     * Publish a typed message to the bus.
     *
     * @param message the message to publish
     * @param targetId the target server ID (null for broadcast)
     * @param <T> the message type
     */
    public <T extends BaseMessage> void publishTyped(T message, String targetId) {
        try {
            String messageType = message.getMessageType();
            JsonNode payload = objectMapper.valueToTree(message);
            
            MessageEnvelope envelope = new MessageEnvelope(
                messageType,
                getServerId(),
                targetId,
                UUID.randomUUID(),
                System.currentTimeMillis(),
                1,
                payload
            );
            
            // Abstract method to be implemented by subclasses
            publishEnvelope(envelope);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to publish typed message", e);
            throw new RuntimeException("Failed to publish typed message", e);
        }
    }
    
    /**
     * Broadcast a typed message to all servers.
     *
     * @param message the message to broadcast
     * @param <T> the message type
     */
    public <T extends BaseMessage> void broadcastTyped(T message) {
        publishTyped(message, null);
    }
    
    /**
     * Process typed handlers for an envelope.
     * This should be called by concrete implementations when handling messages.
     *
     * @param envelope the message envelope
     */
    @SuppressWarnings("unchecked")
    protected void processTypedHandlers(MessageEnvelope envelope) {
        String messageType = envelope.getType();
        List<TypedMessageHandler<?>> handlers = typedSubscriptions.get(messageType);
        
        if (handlers != null && !handlers.isEmpty()) {
            try {
                // Deserialize once for all handlers
                BaseMessage message = messageTypeRegistry.deserialize(messageType, envelope.getPayload());
                
                for (TypedMessageHandler<?> handler : handlers) {
                    try {
                        // Safe cast because we know the type matches
                        ((TypedMessageHandler<BaseMessage>) handler).handle(message, envelope);
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Error in typed message handler", e);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to deserialize for typed handlers: " + messageType, e);
            }
        }
    }
    
    /**
     * Publish an envelope to the message bus.
     * This method must be implemented by concrete subclasses.
     *
     * @param envelope the message envelope to publish
     */
    protected abstract void publishEnvelope(MessageEnvelope envelope);
    
    /**
     * Shuts down the message bus and releases resources.
     * This is a lifecycle method that should clean up any
     * resources without maintaining persistent state.
     */
    public abstract void shutdown();
}
