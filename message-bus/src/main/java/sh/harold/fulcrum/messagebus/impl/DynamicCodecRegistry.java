package sh.harold.fulcrum.messagebus.impl;

import sh.harold.fulcrum.messagebus.CodecRegistry;
import sh.harold.fulcrum.messagebus.MessageBusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic codec registry that allows runtime registration of message types.
 * High-availability services like Registry can register custom message types dynamically.
 * This implementation uses Jackson for JSON serialization/deserialization.
 */
public class DynamicCodecRegistry implements CodecRegistry {
    
    private final ObjectMapper objectMapper;
    private final Map<String, Class<?>> typeRegistry;
    
    public DynamicCodecRegistry() {
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.typeRegistry = new ConcurrentHashMap<>();
    }
    
    @Override
    public void registerType(String messageType, Class<?> messageClass) {
        if (messageType == null || messageType.trim().isEmpty()) {
            throw new IllegalArgumentException("Message type cannot be null or empty");
        }
        
        if (messageClass != null) {
            typeRegistry.put(messageType, messageClass);
        } else {
            // Remove registration to default to Map
            typeRegistry.remove(messageType);
        }
    }
    
    @Override
    public String serialize(String messageType, Object payload) throws MessageBusException {
        if (payload == null) {
            return "null";
        }
        
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw MessageBusException.serializationFailed(messageType, e);
        }
    }
    
    @Override
    public Object deserialize(String messageType, String data) throws MessageBusException {
        if (data == null || "null".equals(data)) {
            return null;
        }
        
        try {
            Class<?> targetClass = typeRegistry.get(messageType);
            if (targetClass != null) {
                return objectMapper.readValue(data, targetClass);
            }
            // Default to Map for unknown types (dynamic messages)
            return objectMapper.readValue(data, Map.class);
        } catch (JsonProcessingException e) {
            throw MessageBusException.deserializationFailed(messageType, e);
        } catch (Exception e) {
            throw MessageBusException.deserializationFailed(messageType, e);
        }
    }
    
    @Override
    public boolean isRegistered(String messageType) {
        return messageType != null && typeRegistry.containsKey(messageType);
    }
    
    @Override
    public Class<?> getRegisteredClass(String messageType) {
        return messageType != null ? typeRegistry.get(messageType) : null;
    }
}