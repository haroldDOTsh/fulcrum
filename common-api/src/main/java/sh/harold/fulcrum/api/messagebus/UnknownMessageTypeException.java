package sh.harold.fulcrum.api.messagebus;

/**
 * Exception thrown when attempting to deserialize an unknown message type.
 */
public class UnknownMessageTypeException extends RuntimeException {

    private final String messageType;

    public UnknownMessageTypeException(String messageType) {
        super("Unknown message type: " + messageType);
        this.messageType = messageType;
    }

    public UnknownMessageTypeException(String messageType, Throwable cause) {
        super("Unknown message type: " + messageType, cause);
        this.messageType = messageType;
    }

    public String getMessageType() {
        return messageType;
    }
}