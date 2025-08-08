package sh.harold.fulcrum.api.messagebus;

/**
 * Functional interface for handling incoming messages.
 * Implementations should process messages based on their type and payload.
 */
@FunctionalInterface
public interface MessageHandler {
    
    /**
     * Handles an incoming message envelope.
     * 
     * @param envelope the message envelope containing metadata and payload
     */
    void handle(MessageEnvelope envelope);
}