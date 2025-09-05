package sh.harold.fulcrum.api.messagebus;

/**
 * Exception thrown when message deserialization fails.
 */
public class MessageDeserializationException extends RuntimeException {
    
    public MessageDeserializationException(String message) {
        super(message);
    }
    
    public MessageDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}