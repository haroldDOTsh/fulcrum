package sh.harold.fulcrum.messagebus.impl;

import sh.harold.fulcrum.messagebus.MessageEnvelope;
import sh.harold.fulcrum.messagebus.adapter.MessageBusAdapter;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * In-memory implementation of MessageBus for development and testing.
 * All messages are routed within the same JVM instance.
 * This implementation is stateless and thread-safe.
 */
public class InMemoryMessageBus extends AbstractMessageBus {
    
    // Simulated network for multi-instance testing
    private static final Map<String, InMemoryMessageBus> instances = new ConcurrentHashMap<>();
    
    // Message queue for this instance
    private final BlockingQueue<MessageEnvelope> incomingMessages = new LinkedBlockingQueue<>();
    private final ExecutorService messageProcessor;
    private volatile boolean running = true;
    
    public InMemoryMessageBus(MessageBusAdapter adapter) {
        super(adapter);
        
        // Register this instance
        instances.put(serverIdentifier, this);
        
        // Start message processor thread
        this.messageProcessor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "InMemoryMessageBus-" + serverIdentifier);
            t.setDaemon(true);
            return t;
        });
        
        messageProcessor.submit(this::processMessages);
        
        // Schedule periodic cleanup
        scheduler.scheduleAtFixedRate(this::cleanupSubscriptions, 60, 60, TimeUnit.SECONDS);
        
        logger.info("InMemoryMessageBus initialized for server: " + serverIdentifier);
    }
    
    @Override
    public CompletableFuture<Void> broadcast(String messageType, Object payload) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Create envelope
                MessageEnvelope envelope = new MessageEnvelope(
                    messageType, serverIdentifier, payload
                );
                
                // Send to all instances (including self)
                for (InMemoryMessageBus instance : instances.values()) {
                    instance.receiveMessage(envelope);
                }
                
                logger.log(Level.FINE, "Broadcast message: " + messageType);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to broadcast message", e);
                throw new RuntimeException(e);
            }
        }, scheduler);
    }
    
    @Override
    public CompletableFuture<Void> send(String targetServer, String messageType, Object payload) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Create envelope
                MessageEnvelope envelope = new MessageEnvelope(
                    messageType, serverIdentifier, targetServer, payload, null
                );
                
                // Find target instance
                InMemoryMessageBus targetInstance = instances.get(targetServer);
                if (targetInstance != null) {
                    targetInstance.receiveMessage(envelope);
                    logger.log(Level.FINE, String.format(
                        "Sent message %s to %s", messageType, targetServer
                    ));
                } else {
                    logger.log(Level.WARNING, "Target server not found: " + targetServer);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to send message", e);
                throw new RuntimeException(e);
            }
        }, scheduler);
    }
    
    @Override
    public boolean isConnected() {
        return running;
    }
    
    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            logger.info("Shutting down InMemoryMessageBus for: " + serverIdentifier);
            
            running = false;
            instances.remove(serverIdentifier);
            
            // Shutdown message processor
            messageProcessor.shutdown();
            try {
                if (!messageProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                    messageProcessor.shutdownNow();
                }
            } catch (InterruptedException e) {
                messageProcessor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            // Clear messages
            incomingMessages.clear();
            
            logger.info("InMemoryMessageBus shutdown complete");
        });
    }
    
    /**
     * Receive a message into this instance's queue.
     */
    private void receiveMessage(MessageEnvelope envelope) {
        if (!running) {
            return;
        }
        
        // Filter messages not for this instance
        if (envelope.getTargetServer() != null && 
            !serverIdentifier.equals(envelope.getTargetServer())) {
            return;
        }
        
        // Don't process our own broadcasts unless we have subscribers
        if (envelope.isBroadcast() && 
            serverIdentifier.equals(envelope.getSourceServer())) {
            // Check if we have any subscribers for this message type
            boolean hasSubscribers = directSubscriptions.containsKey(envelope.getMessageType()) ||
                                    patternSubscriptions.keySet().stream()
                                        .anyMatch(p -> p.matcher(envelope.getMessageType()).matches());
            if (!hasSubscribers) {
                return;
            }
        }
        
        try {
            incomingMessages.offer(envelope, 100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while queuing message", e);
        }
    }
    
    /**
     * Process messages from the queue.
     */
    private void processMessages() {
        while (running) {
            try {
                MessageEnvelope envelope = incomingMessages.poll(100, TimeUnit.MILLISECONDS);
                if (envelope != null) {
                    processIncomingMessage(envelope);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error processing message", e);
            }
        }
    }
    
    /**
     * Get all active InMemory instances (for testing).
     */
    public static Set<String> getActiveInstances() {
        return new HashSet<>(instances.keySet());
    }
    
    /**
     * Clear all instances (for testing).
     */
    public static void clearAllInstances() {
        instances.clear();
    }
}