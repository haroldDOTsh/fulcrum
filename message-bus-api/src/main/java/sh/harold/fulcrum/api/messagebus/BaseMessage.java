package sh.harold.fulcrum.api.messagebus;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

/**
 * Base interface for all strongly-typed messages in the message bus system.
 * All messages that need to be sent through the message bus should implement this interface.
 * <p>
 * This provides type safety and ensures proper serialization/deserialization across the network.
 */
public interface BaseMessage extends Serializable {
    /**
     * Get the message type identifier for this message.
     * This is typically provided by the @MessageType annotation.
     *
     * @return the unique type identifier for this message class
     */
    @JsonIgnore
    default String getMessageType() {
        MessageType annotation = this.getClass().getAnnotation(MessageType.class);
        if (annotation != null) {
            return annotation.value();
        }
        // Fallback to class name if no annotation
        return this.getClass().getName();
    }

    /**
     * Validate that this message contains all required data.
     * Subclasses should override this to provide validation logic.
     *
     * @throws IllegalStateException if the message is invalid
     */
    default void validate() {
        // Default implementation does nothing
    }
}