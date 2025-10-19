package sh.harold.fulcrum.api.messagebus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry for message types that handles type registration and deserialization.
 * This is the central component for type-safe message handling in the message bus.
 */
public class MessageTypeRegistry {

    private static final Logger LOGGER = Logger.getLogger(MessageTypeRegistry.class.getName());

    // Singleton instance
    private static final MessageTypeRegistry INSTANCE = new MessageTypeRegistry();

    private final Map<String, Class<? extends BaseMessage>> typeToClass = new ConcurrentHashMap<>();
    private final Map<Class<? extends BaseMessage>, String> classToType = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    private MessageTypeRegistry() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        registerBuiltInTypes();
    }

    /**
     * Get the singleton instance of the registry.
     */
    public static MessageTypeRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register built-in message types.
     */
    private void registerBuiltInTypes() {
        // Pre-register known message types to avoid reflection at runtime
        try {
            // Register core message types
            register("server.registration.request",
                    sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationRequest.class);
            register("server.registration.response",
                    sh.harold.fulcrum.api.messagebus.messages.ServerRegistrationResponse.class);
            register("server.heartbeat",
                    sh.harold.fulcrum.api.messagebus.messages.ServerHeartbeatMessage.class);
            register("server.removal",
                    sh.harold.fulcrum.api.messagebus.messages.ServerRemovalNotification.class);
            register("server.evacuation.request",
                    sh.harold.fulcrum.api.messagebus.messages.ServerEvacuationRequest.class);
            register("server.evacuation.response",
                    sh.harold.fulcrum.api.messagebus.messages.ServerEvacuationResponse.class);
            register("slot.status.update",
                    sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage.class);
            register("slot.family.advertisement",
                    sh.harold.fulcrum.api.messagebus.messages.SlotFamilyAdvertisementMessage.class);
            register("slot.provision.command",
                    sh.harold.fulcrum.api.messagebus.messages.SlotProvisionCommand.class);
            register("player.locate.request",
                    sh.harold.fulcrum.api.messagebus.messages.PlayerLocateRequest.class);
            register("player.locate.response",
                    sh.harold.fulcrum.api.messagebus.messages.PlayerLocateResponse.class);
            register(ChannelConstants.PLAYER_RESERVATION_REQUEST,
                    sh.harold.fulcrum.api.messagebus.messages.PlayerReservationRequest.class);
            register(ChannelConstants.PLAYER_RESERVATION_RESPONSE,
                    sh.harold.fulcrum.api.messagebus.messages.PlayerReservationResponse.class);
            register(ChannelConstants.PARTY_UPDATE,
                    sh.harold.fulcrum.api.messagebus.messages.party.PartyUpdateMessage.class);
            register(ChannelConstants.PARTY_RESERVATION_CREATED,
                    sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationCreatedMessage.class);
            register(ChannelConstants.PARTY_RESERVATION_CLAIMED,
                    sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationClaimedMessage.class);
            register(ChannelConstants.PARTY_WARP_REQUEST,
                    sh.harold.fulcrum.api.messagebus.messages.party.PartyWarpRequestMessage.class);
            register(ChannelConstants.MATCH_ROSTER_CREATED,
                    sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterCreatedMessage.class);
            register(ChannelConstants.MATCH_ROSTER_ENDED,
                    sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterEndedMessage.class);
            register(ChannelConstants.REGISTRY_RANK_MUTATION_REQUEST,
                    sh.harold.fulcrum.api.messagebus.messages.rank.RankMutationRequestMessage.class);
            register(ChannelConstants.REGISTRY_RANK_MUTATION_RESPONSE,
                    sh.harold.fulcrum.api.messagebus.messages.rank.RankMutationResponseMessage.class);
            register(ChannelConstants.REGISTRY_RANK_UPDATE,
                    sh.harold.fulcrum.api.messagebus.messages.rank.RankSyncMessage.class);

            LOGGER.info("Registered " + typeToClass.size() + " built-in message types");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to register some built-in message types", e);
        }
    }

    /**
     * Register a message type with its class.
     *
     * @param messageType  the unique type identifier
     * @param messageClass the message class
     */
    public void register(String messageType, Class<? extends BaseMessage> messageClass) {
        if (messageType == null || messageClass == null) {
            throw new IllegalArgumentException("Message type and class cannot be null");
        }

        Class<? extends BaseMessage> existingClass = typeToClass.get(messageType);
        if (existingClass != null && !existingClass.equals(messageClass)) {
            LOGGER.warning("Overwriting message type: " + messageType +
                    " (was: " + existingClass.getName() + ", now: " + messageClass.getName() + ")");
        }

        typeToClass.put(messageType, messageClass);
        classToType.put(messageClass, messageType);

        LOGGER.fine("Registered message type: " + messageType + " -> " + messageClass.getName());
    }

    /**
     * Register a message class by scanning its @MessageType annotation.
     *
     * @param messageClass the message class to register
     */
    public void registerByAnnotation(Class<? extends BaseMessage> messageClass) {
        MessageType annotation = messageClass.getAnnotation(MessageType.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Class " + messageClass.getName() +
                    " must have @MessageType annotation");
        }
        register(annotation.value(), messageClass);
    }

    /**
     * Get the message type identifier for a message instance.
     *
     * @param message the message instance
     * @return the type identifier, or null if not registered
     */
    public String getTypeId(BaseMessage message) {
        if (message == null) {
            return null;
        }

        // First check the registry
        String typeId = classToType.get(message.getClass());
        if (typeId != null) {
            return typeId;
        }

        // Fallback to annotation or default
        return message.getMessageType();
    }

    /**
     * Deserialize a JsonNode payload to the correct message type.
     *
     * @param messageType the type identifier
     * @param payload     the JSON payload
     * @return the deserialized message
     * @throws UnknownMessageTypeException if the type is not registered
     */
    @SuppressWarnings("unchecked")
    public <T extends BaseMessage> T deserialize(String messageType, JsonNode payload)
            throws UnknownMessageTypeException {
        if (messageType == null) {
            throw new UnknownMessageTypeException("Message type is null");
        }

        Class<? extends BaseMessage> messageClass = typeToClass.get(messageType);
        if (messageClass == null) {
            // Try to handle backwards compatibility with Map-based messages
            LOGGER.warning("Unknown message type: " + messageType + " - will return as Map");
            throw new UnknownMessageTypeException(messageType);
        }

        try {
            T message = (T) objectMapper.treeToValue(payload, messageClass);

            // Validate the message if it implements validation
            if (message != null) {
                message.validate();
            }

            return message;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to deserialize message type: " + messageType, e);
            throw new UnknownMessageTypeException(messageType, e);
        }
    }

    /**
     * Deserialize a JsonNode payload to a specific class.
     * This is used when we know the expected type.
     *
     * @param payload     the JSON payload
     * @param targetClass the target class
     * @return the deserialized message
     */
    @SuppressWarnings("unchecked")
    public <T extends BaseMessage> T deserializeToClass(JsonNode payload, Class<T> targetClass) {
        try {
            T message = objectMapper.treeToValue(payload, targetClass);

            // Validate the message if it implements validation
            if (message != null) {
                message.validate();
            }

            return message;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to deserialize to class: " + targetClass.getName(), e);
            throw new MessageDeserializationException("Failed to deserialize to " + targetClass.getName(), e);
        }
    }

    /**
     * Convert any object to JsonNode for envelope payload.
     *
     * @param obj the object to convert
     * @return the JsonNode representation
     */
    public JsonNode toJsonNode(Object obj) {
        return objectMapper.valueToTree(obj);
    }

    /**
     * Check if a message type is registered.
     *
     * @param messageType the type identifier
     * @return true if registered
     */
    public boolean isRegistered(String messageType) {
        return typeToClass.containsKey(messageType);
    }

    /**
     * Get the class for a message type.
     *
     * @param messageType the type identifier
     * @return the message class, or null if not registered
     */
    public Class<? extends BaseMessage> getMessageClass(String messageType) {
        return typeToClass.get(messageType);
    }

    /**
     * Check if a message type is registered.
     *
     * @param messageType the message type to check
     * @return true if registered, false otherwise
     */
    public boolean isTypeRegistered(String messageType) {
        return typeToClass.containsKey(messageType);
    }

    /**
     * Get the message type for a class.
     *
     * @param messageClass the message class
     * @return the message type, or null if not registered
     */
    public String getTypeForClass(Class<? extends BaseMessage> messageClass) {
        return classToType.get(messageClass);
    }

    /**
     * Clear all registrations (mainly for testing).
     */
    public void clear() {
        typeToClass.clear();
        classToType.clear();
    }

    /**
     * Get the number of registered types.
     */
    public int size() {
        return typeToClass.size();
    }
}
