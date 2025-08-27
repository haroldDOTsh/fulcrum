package sh.harold.fulcrum.messagebus.impl;

import sh.harold.fulcrum.messagebus.*;
import sh.harold.fulcrum.messagebus.adapter.MessageBusAdapter;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Abstract base implementation of MessageBus providing common functionality.
 * Handles subscriptions, request/response correlation, and message routing.
 */
public abstract class AbstractMessageBus implements MessageBus {
    
    protected final MessageBusAdapter adapter;
    protected final String serverIdentifier;
    protected final Logger logger;
    protected final ScheduledExecutorService scheduler;
    protected CodecRegistry codecRegistry;
    
    // Subscription management
    private final Map<String, Set<SubscriptionImpl>> directSubscriptions = new ConcurrentHashMap<>();
    private final Map<Pattern, Set<SubscriptionImpl>> patternSubscriptions = new ConcurrentHashMap<>();
    
    // Request/Response tracking
    private final Map<String, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, MessageHandler> requestHandlers = new ConcurrentHashMap<>();
    
    protected AbstractMessageBus(MessageBusAdapter adapter) {
        this.adapter = adapter;
        this.serverIdentifier = adapter.getServerIdentifier();
        this.logger = adapter.getLogger();
        this.scheduler = adapter.getScheduler();
        this.codecRegistry = new DynamicCodecRegistry();
    }
    
    public void setCodecRegistry(CodecRegistry codecRegistry) {
        this.codecRegistry = codecRegistry;
    }
    
    @Override
    public CodecRegistry getCodecRegistry() {
        return codecRegistry;
    }
    
    @Override
    public Subscription subscribe(String messageType, Consumer<MessageEnvelope> handler) {
        if (messageType == null || handler == null) {
            throw new IllegalArgumentException("Message type and handler cannot be null");
        }
        
        SubscriptionImpl subscription = new SubscriptionImpl(messageType, handler, false);
        directSubscriptions.computeIfAbsent(messageType, k -> ConcurrentHashMap.newKeySet())
                          .add(subscription);
        
        logger.log(Level.FINE, "Subscribed to message type: " + messageType);
        return subscription;
    }
    
    @Override
    public Subscription subscribePattern(String pattern, Consumer<MessageEnvelope> handler) {
        if (pattern == null || handler == null) {
            throw new IllegalArgumentException("Pattern and handler cannot be null");
        }
        
        Pattern compiledPattern = Pattern.compile(pattern);
        SubscriptionImpl subscription = new SubscriptionImpl(pattern, handler, true);
        patternSubscriptions.computeIfAbsent(compiledPattern, k -> ConcurrentHashMap.newKeySet())
                           .add(subscription);
        
        logger.log(Level.FINE, "Subscribed to pattern: " + pattern);
        return subscription;
    }
    
    @Override
    public <T> CompletableFuture<T> request(String targetServer, String messageType, 
                                           Object payload, Class<T> responseType) {
        String correlationId = UUID.randomUUID().toString();
        String requestType = messageType + ".request";
        String responsePattern = messageType + ".response";
        
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);
        
        // Set timeout
        long timeoutMs = adapter.getConnectionConfig().getMessageTimeout().toMillis();
        scheduler.schedule(() -> {
            if (pendingRequests.remove(correlationId) != null) {
                future.completeExceptionally(
                    MessageBusException.timeout("Request " + messageType)
                );
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        
        // Subscribe to response
        subscribe(responsePattern, envelope -> {
            if (correlationId.equals(envelope.getCorrelationId())) {
                CompletableFuture<Object> pending = pendingRequests.remove(correlationId);
                if (pending != null) {
                    pending.complete(envelope.getPayload());
                }
            }
        });
        
        // Send request
        MessageEnvelope requestEnvelope = new MessageEnvelope(
            requestType, serverIdentifier, targetServer, payload, correlationId
        );
        
        if (targetServer != null) {
            send(targetServer, requestType, payload);
        } else {
            broadcast(requestType, payload);
        }
        
        return future.thenApply(response -> {
            if (responseType.isInstance(response)) {
                return responseType.cast(response);
            } else {
                throw new ClassCastException("Response type mismatch");
            }
        });
    }
    
    @Override
    public Subscription onRequest(String messageType, MessageHandler handler) {
        if (messageType == null || handler == null) {
            throw new IllegalArgumentException("Message type and handler cannot be null");
        }
        
        String requestType = messageType + ".request";
        requestHandlers.put(requestType, handler);
        
        // Subscribe to handle requests
        return subscribe(requestType, envelope -> {
            MessageHandler requestHandler = requestHandlers.get(envelope.getMessageType());
            if (requestHandler != null && envelope.getCorrelationId() != null) {
                // Handle request asynchronously
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return requestHandler.handle(envelope).get();
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error handling request", e);
                        return null;
                    }
                }, scheduler).thenAccept(response -> {
                    if (response != null) {
                        // Send response
                        String responseType = messageType + ".response";
                        MessageEnvelope responseEnvelope = new MessageEnvelope(
                            responseType, serverIdentifier, envelope.getSourceServer(),
                            response, envelope.getCorrelationId()
                        );
                        send(envelope.getSourceServer(), responseType, response);
                    }
                });
            }
        });
    }
    
    /**
     * Process an incoming message envelope by routing to appropriate handlers.
     */
    protected void processIncomingMessage(MessageEnvelope envelope) {
        String messageType = envelope.getMessageType();
        
        // Direct subscribers
        Set<SubscriptionImpl> directSubs = directSubscriptions.get(messageType);
        if (directSubs != null) {
            for (SubscriptionImpl sub : directSubs) {
                if (sub.isActive()) {
                    try {
                        sub.handler.accept(envelope);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error in message handler", e);
                    }
                }
            }
        }
        
        // Pattern subscribers
        for (Map.Entry<Pattern, Set<SubscriptionImpl>> entry : patternSubscriptions.entrySet()) {
            if (entry.getKey().matcher(messageType).matches()) {
                for (SubscriptionImpl sub : entry.getValue()) {
                    if (sub.isActive()) {
                        try {
                            sub.handler.accept(envelope);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error in pattern handler", e);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Cleanup inactive subscriptions periodically.
     */
    protected void cleanupSubscriptions() {
        // Remove inactive direct subscriptions
        directSubscriptions.values().forEach(subs -> 
            subs.removeIf(sub -> !sub.isActive())
        );
        
        // Remove inactive pattern subscriptions
        patternSubscriptions.values().forEach(subs -> 
            subs.removeIf(sub -> !sub.isActive())
        );
    }
    
    /**
     * Internal subscription implementation.
     */
    private class SubscriptionImpl implements Subscription {
        private final String identifier;
        private final Consumer<MessageEnvelope> handler;
        private final boolean isPattern;
        private volatile boolean active = true;
        
        SubscriptionImpl(String identifier, Consumer<MessageEnvelope> handler, boolean isPattern) {
            this.identifier = identifier;
            this.handler = handler;
            this.isPattern = isPattern;
        }
        
        @Override
        public void unsubscribe() {
            active = false;
            
            if (isPattern) {
                // Remove from pattern subscriptions
                patternSubscriptions.values().forEach(subs -> subs.remove(this));
            } else {
                // Remove from direct subscriptions
                Set<SubscriptionImpl> subs = directSubscriptions.get(identifier);
                if (subs != null) {
                    subs.remove(this);
                }
            }
            
            logger.log(Level.FINE, "Unsubscribed from: " + identifier);
        }
        
        @Override
        public boolean isActive() {
            return active;
        }
    }
}