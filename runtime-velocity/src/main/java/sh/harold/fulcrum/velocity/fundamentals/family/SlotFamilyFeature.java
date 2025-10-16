package sh.harold.fulcrum.velocity.fundamentals.family;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ServerRemovalNotification;
import sh.harold.fulcrum.api.messagebus.messages.SlotFamilyAdvertisementMessage;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for slot family advertisements from the registry and caches them for proxy features.
 */
public final class SlotFamilyFeature implements VelocityFeature {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final SlotFamilyCache cache = new SlotFamilyCache();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private MessageBus messageBus;
    private Logger logger;
    private MessageHandler advertisementHandler;
    private MessageHandler removalHandler;
    private MessageHandler statusHandler;

    @Override
    public String getName() {
        return "SlotFamilyCache";
    }

    @Override
    public int getPriority() {
        return 25; // After DataAPI (20) but before server lifecycle setup (30)
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;
        this.messageBus = serviceLocator.getRequiredService(MessageBus.class);

        advertisementHandler = this::handleAdvertisement;
        removalHandler = this::handleRemoval;
        statusHandler = this::handleSlotStatus;

        messageBus.subscribe(ChannelConstants.REGISTRY_SLOT_FAMILY_ADVERTISEMENT, advertisementHandler);
        messageBus.subscribe(ChannelConstants.SERVER_REMOVAL_NOTIFICATION, removalHandler);
        messageBus.subscribe(ChannelConstants.REGISTRY_SLOT_STATUS, statusHandler);

        serviceLocator.register(SlotFamilyCache.class, cache);
        running.set(true);

        logger.info("SlotFamilyFeature initialized - listening for registry family advertisements");
    }

    @Override
    public void shutdown() {
        if (!running.getAndSet(false)) {
            return;
        }
        if (messageBus != null) {
            if (advertisementHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_SLOT_FAMILY_ADVERTISEMENT, advertisementHandler);
            }
            if (removalHandler != null) {
                messageBus.unsubscribe(ChannelConstants.SERVER_REMOVAL_NOTIFICATION, removalHandler);
            }
            if (statusHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_SLOT_STATUS, statusHandler);
            }
        }
        cache.clear();
        logger.info("SlotFamilyFeature shut down");
    }

    private void handleAdvertisement(MessageEnvelope envelope) {
        if (envelope == null) {
            return;
        }

        Object payload = envelope.getPayload();
        SlotFamilyAdvertisementMessage message = convert(payload, SlotFamilyAdvertisementMessage.class);
        if (message == null) {
            return;
        }

        String serverId = message.getServerId();
        Map<String, Integer> capacities = message.getFamilyCapacities();
        cache.updateCapacities(serverId, capacities);
        cache.updateVariants(serverId, message.getFamilyVariants());

        if (logger.isDebugEnabled()) {
            logger.debug("Updated slot family cache for {} => {}", serverId, capacities);
        }
    }

    private void handleRemoval(MessageEnvelope envelope) {
        if (envelope == null) {
            return;
        }

        Object payload = envelope.getPayload();
        String serverId = extractServerId(payload);
        if (serverId == null || serverId.isBlank()) {
            return;
        }

        cache.remove(serverId);
        if (logger.isDebugEnabled()) {
            logger.debug("Removed slot family data for {}", serverId);
        }
    }

    private void handleSlotStatus(MessageEnvelope envelope) {
        if (envelope == null) {
            return;
        }
        SlotStatusUpdateMessage status = convert(envelope.getPayload(), SlotStatusUpdateMessage.class);
        if (status == null) {
            return;
        }

        String serverId = status.getServerId();
        Map<String, String> metadata = status.getMetadata();
        String familyId = null;
        if (metadata != null) {
            familyId = firstNonBlank(metadata.get("family"), metadata.get("familyId"));
        }
        familyId = normalize(familyId);
        if (familyId == null) {
            return;
        }

        String variantId = null;
        if (metadata != null) {
            variantId = firstNonBlank(metadata.get("variant"), metadata.get("variantId"));
        }
        if (variantId == null || variantId.isBlank()) {
            String gameType = status.getGameType();
            if (gameType != null && !gameType.isBlank() && !gameType.equalsIgnoreCase(familyId)) {
                variantId = gameType;
            }
        }

        variantId = normalize(variantId);
        if (variantId != null) {
            cache.recordVariant(serverId, familyId, variantId);
            if (logger.isTraceEnabled()) {
                logger.trace("Recorded variant {} for family {} on {}", variantId, familyId, serverId);
            }
        }
    }

    private String extractServerId(Object payload) {
        if (payload == null) {
            return null;
        }

        if (payload instanceof String textual) {
            return textual;
        }

        if (payload instanceof JsonNode node) {
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.hasNonNull("serverId")) {
                return node.get("serverId").asText();
            }
            ServerRemovalNotification notification = convert(node, ServerRemovalNotification.class);
            return notification != null ? notification.getServerId() : null;
        }

        if (payload instanceof ServerRemovalNotification notification) {
            return notification.getServerId();
        }

        ServerRemovalNotification notification = convert(payload, ServerRemovalNotification.class);
        return notification != null ? notification.getServerId() : null;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private <T> T convert(Object payload, Class<T> type) {
        if (payload == null) {
            return null;
        }
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        try {
            if (payload instanceof JsonNode node) {
                return objectMapper.treeToValue(node, type);
            }
            return objectMapper.convertValue(payload, type);
        } catch (Exception exception) {
            logger.warn("Failed to convert {} to {}", payload.getClass().getName(), type.getSimpleName(), exception);
            return null;
        }
    }
}
