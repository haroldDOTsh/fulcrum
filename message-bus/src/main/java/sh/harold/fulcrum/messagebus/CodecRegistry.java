package sh.harold.fulcrum.messagebus;

/**
 * Registry for dynamic message type serialization/deserialization.
 * Allows runtime registration of message types without hard-coding.
 */
public interface CodecRegistry {
    
    /**
     * Register a message type with optional class for deserialization.
     * 
     * @param messageType The message type identifier
     * @param messageClass The class to use for deserialization (can be null for Map)
     */
    void registerType(String messageType, Class<?> messageClass);
    
    /**
     * Serialize a message payload to a string (usually JSON).
     * 
     * @param messageType The message type identifier
     * @param payload The payload to serialize
     * @return The serialized string representation
     * @throws MessageBusException if serialization fails
     */
    String serialize(String messageType, Object payload) throws MessageBusException;
    
    /**
     * Deserialize a message payload from a string.
     * 
     * @param messageType The message type identifier
     * @param data The serialized data
     * @return The deserialized object
     * @throws MessageBusException if deserialization fails
     */
    Object deserialize(String messageType, String data) throws MessageBusException;
    
    /**
     * Check if a message type is registered.
     * 
     * @param messageType The message type to check
     * @return true if registered, false otherwise
     */
    boolean isRegistered(String messageType);
    
    /**
     * Get the registered class for a message type.
     * 
     * @param messageType The message type
     * @return The registered class, or null if not registered or using Map
     */
    Class<?> getRegisteredClass(String messageType);
}