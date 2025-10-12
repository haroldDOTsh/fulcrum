package sh.harold.fulcrum.api.messagebus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for message type codecs.
 * Manages the serialization and deserialization of message payloads.
 */
public class CodecRegistry {

    private final Map<String, Class<?>> typeRegistry = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    /**
     * Creates a new codec registry with a default ObjectMapper.
     */
    public CodecRegistry() {
        this(new ObjectMapper());
    }

    /**
     * Creates a new codec registry with a custom ObjectMapper.
     *
     * @param objectMapper the Jackson ObjectMapper to use
     */
    public CodecRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Registers a message type with its corresponding class.
     *
     * @param type         the message type identifier
     * @param messageClass the class associated with this message type
     */
    public void register(String type, Class<?> messageClass) {
        if (type == null || messageClass == null) {
            throw new IllegalArgumentException("Type and message class cannot be null");
        }
        typeRegistry.put(type, messageClass);
    }

    /**
     * Deserializes a JSON payload to the specified class type.
     *
     * @param <T>           the expected return type
     * @param type          the message type identifier
     * @param payload       the JSON payload to deserialize
     * @param expectedClass the expected class type
     * @return the deserialized object
     * @throws IllegalArgumentException if the type is not registered or deserialization fails
     */
    public <T> T deserialize(String type, JsonNode payload, Class<T> expectedClass) {
        if (!typeRegistry.containsKey(type)) {
            throw new IllegalArgumentException("Unknown message type: " + type);
        }

        Class<?> registeredClass = typeRegistry.get(type);
        if (!expectedClass.isAssignableFrom(registeredClass)) {
            throw new IllegalArgumentException(
                    String.format("Type mismatch for message type '%s': expected %s but registered %s",
                            type, expectedClass.getName(), registeredClass.getName())
            );
        }

        try {
            Object result = objectMapper.treeToValue(payload, registeredClass);
            return expectedClass.cast(result);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize payload for type: " + type, e);
        }
    }

    /**
     * Serializes a message object to JSON.
     *
     * @param type    the message type identifier
     * @param message the message object to serialize
     * @return the serialized JsonNode
     * @throws IllegalArgumentException if serialization fails
     */
    public JsonNode serialize(String type, Object message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        try {
            return objectMapper.valueToTree(message);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize message for type: " + type, e);
        }
    }

    /**
     * Checks if a message type is registered.
     *
     * @param type the message type identifier
     * @return true if the type is registered, false otherwise
     */
    public boolean isRegistered(String type) {
        return typeRegistry.containsKey(type);
    }

    /**
     * Gets the registered class for a message type.
     *
     * @param type the message type identifier
     * @return the registered class, or null if not found
     */
    public Class<?> getRegisteredClass(String type) {
        return typeRegistry.get(type);
    }
}