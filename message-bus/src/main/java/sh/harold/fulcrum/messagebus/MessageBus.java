package sh.harold.fulcrum.messagebus;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Core message bus interface for inter-service communication.
 * This interface is stateless and supports dynamic message types.
 */
public interface MessageBus {
    
    /**
     * Broadcast a message to all connected services.
     * 
     * @param messageType The message type identifier (e.g., "player.join")
     * @param payload The message payload (can be any object)
     * @return CompletableFuture that completes when message is sent
     */
    CompletableFuture<Void> broadcast(String messageType, Object payload);
    
    /**
     * Send a message directly to a specific server/proxy.
     * 
     * @param targetServer The target server identifier
     * @param messageType The message type identifier
     * @param payload The message payload
     * @return CompletableFuture that completes when message is sent
     */
    CompletableFuture<Void> send(String targetServer, String messageType, Object payload);
    
    /**
     * Subscribe to messages of a specific type.
     * 
     * @param messageType The message type to subscribe to
     * @param handler The handler to process incoming messages
     * @return Subscription handle for unsubscribing
     */
    Subscription subscribe(String messageType, Consumer<MessageEnvelope> handler);
    
    /**
     * Subscribe to all messages matching a pattern.
     * 
     * @param pattern The pattern to match (e.g., "player.*")
     * @param handler The handler to process incoming messages
     * @return Subscription handle for unsubscribing
     */
    Subscription subscribePattern(String pattern, Consumer<MessageEnvelope> handler);
    
    /**
     * Send a request and await a response.
     * 
     * @param targetServer The target server (null for broadcast request)
     * @param messageType The request message type
     * @param payload The request payload
     * @param responseType The expected response type class
     * @param <T> The response type
     * @return CompletableFuture containing the response
     */
    <T> CompletableFuture<T> request(String targetServer, String messageType, Object payload, Class<T> responseType);
    
    /**
     * Register a request handler.
     * 
     * @param messageType The request message type to handle
     * @param handler The handler that processes requests and returns responses
     * @return Subscription handle for unregistering
     */
    Subscription onRequest(String messageType, MessageHandler handler);
    
    /**
     * Get the codec registry for dynamic message registration.
     * 
     * @return The codec registry
     */
    CodecRegistry getCodecRegistry();
    
    /**
     * Check if the message bus is connected.
     * 
     * @return true if connected, false otherwise
     */
    boolean isConnected();
    
    /**
     * Shutdown the message bus and release resources.
     * 
     * @return CompletableFuture that completes when shutdown is done
     */
    CompletableFuture<Void> shutdown();
    
    /**
     * Subscription handle for unsubscribing from messages.
     */
    interface Subscription {
        /**
         * Unsubscribe from messages.
         */
        void unsubscribe();
        
        /**
         * Check if this subscription is still active.
         * 
         * @return true if active, false if unsubscribed
         */
        boolean isActive();
    }
}