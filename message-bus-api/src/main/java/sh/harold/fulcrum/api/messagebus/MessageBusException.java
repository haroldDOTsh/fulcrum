package sh.harold.fulcrum.api.messagebus;

/**
 * Exception thrown by message bus operations.
 */
public class MessageBusException extends RuntimeException {
    
    public MessageBusException(String message) {
        super(message);
    }
    
    public MessageBusException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public MessageBusException(Throwable cause) {
        super(cause);
    }
    
    /**
     * Create an exception for connection failures.
     */
    public static MessageBusException connectionFailed(String details, Throwable cause) {
        return new MessageBusException("Message bus connection failed: " + details, cause);
    }
    
    /**
     * Create an exception for serialization failures.
     */
    public static MessageBusException serializationFailed(String messageType, Throwable cause) {
        return new MessageBusException("Failed to serialize message type: " + messageType, cause);
    }
    
    /**
     * Create an exception for deserialization failures.
     */
    public static MessageBusException deserializationFailed(String messageType, Throwable cause) {
        return new MessageBusException("Failed to deserialize message type: " + messageType, cause);
    }
    
    /**
     * Create an exception for timeout.
     */
    public static MessageBusException timeout(String operation) {
        return new MessageBusException("Operation timed out: " + operation);
    }
}