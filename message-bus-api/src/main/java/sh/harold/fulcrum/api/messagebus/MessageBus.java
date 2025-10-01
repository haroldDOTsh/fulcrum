package sh.harold.fulcrum.api.messagebus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Core interface for server-to-server messaging.
 * Provides methods for broadcasting, targeted messaging, and request-response patterns.
 */
public interface MessageBus {
    
    /**
     * Broadcasts a message to all connected servers.
     * 
     * @param type the message type identifier
     * @param payload the message payload object
     */
    void broadcast(String type, Object payload);
    
    /**
     * Sends a message to a specific server.
     * 
     * @param targetServerId the target server identifier
     * @param type the message type identifier
     * @param payload the message payload object
     */
    void send(String targetServerId, String type, Object payload);
    
    /**
     * Sends a request to a specific server and waits for a response.
     * 
     * @param targetServerId the target server identifier
     * @param type the message type identifier
     * @param payload the request payload object
     * @param timeout the maximum time to wait for a response
     * @return a CompletableFuture containing the response object
     */
    CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout);
    
    /**
     * Subscribes a handler to messages of a specific type.
     * 
     * @param type the message type to subscribe to
     * @param handler the handler to process messages
     */
    void subscribe(String type, MessageHandler handler);
    
    /**
     * Unsubscribes a handler from messages of a specific type.
     * 
     * @param type the message type to unsubscribe from
     * @param handler the handler to remove
     */
    void unsubscribe(String type, MessageHandler handler);

    /**
     * Refresh the transport's notion of this server's identifier.
     * Implementations should resubscribe to any identity-based channels
     * when the runtime receives a new permanent server ID.
     */
    default void refreshServerIdentity() {
        // Default implementations may ignore this.
    }

    /**
     * @return the current server identifier used by the transport layer, or null if unknown.
     */
    default String currentServerId() {
        return null;
    }
}
