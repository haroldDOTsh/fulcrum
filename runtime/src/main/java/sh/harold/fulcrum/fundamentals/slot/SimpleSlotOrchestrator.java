package sh.harold.fulcrum.fundamentals.slot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.SlotFamilyAdvertisementMessage;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.SlotProvisionCommand;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;

/**
 * Pragmatic orchestration layer that advertises slot families and mints logical slots on demand.
 */
public class SimpleSlotOrchestrator {
    private static final Logger LOGGER = Logger.getLogger(SimpleSlotOrchestrator.class.getName());

    private final MessageBus messageBus;
    private final ServerIdentifier serverIdentifier;
    private final AtomicBoolean active = new AtomicBoolean(false);

    private final Map<String, FamilyProfile> families = new ConcurrentHashMap<>();
    private volatile int serverMaxPlayers;
    private volatile String environment;
    private volatile String primaryFamily;

    public SimpleSlotOrchestrator(MessageBus messageBus, ServerIdentifier serverIdentifier) {
        this.messageBus = messageBus;
        this.serverIdentifier = serverIdentifier;
    }

    /**
     * Configure the slot families the server can host along with max concurrent slots per family.
     */
    public void configureFamilies(Map<String, Integer> familyCapacities) {
        families.clear();
        if (familyCapacities != null) {
            familyCapacities.forEach((family, maxSlots) -> {
                if (family == null || family.isBlank()) {
                    return;
                }
                families.put(family, new FamilyProfile(family, Math.max(1, maxSlots)));
            });
        }
        primaryFamily = families.keySet().stream().findFirst().orElse(primaryFamily);
    }

    /**
     * Called once the backend has a permanent ID and is ready to advertise.
     */
    public void onServerRegistered(String fallbackFamily, String environment, int maxPlayers) {
        this.environment = environment;
        this.serverMaxPlayers = maxPlayers;
        if (families.isEmpty() && fallbackFamily != null) {
            families.put(fallbackFamily, new FamilyProfile(fallbackFamily, 1));
        }
        primaryFamily = families.keySet().stream().findFirst().orElse(fallbackFamily);
        active.set(true);
        advertiseFamilies();
    }

    /**
     * Get the default family for this orchestrator.
     */
    public String getPrimaryFamily() {
        if (primaryFamily != null) {
            return primaryFamily;
        }
        return families.keySet().stream().findFirst().orElse(null);
    }

    /**
     * Broadcast the server's family capabilities to the registry.
     */
    public void advertiseFamilies() {
        if (!active.get() || families.isEmpty()) {
            return;
        }
        Map<String, Integer> payload = new HashMap<>();
        families.values().forEach(profile -> payload.put(profile.name, profile.maxSlots));
        SlotFamilyAdvertisementMessage message = new SlotFamilyAdvertisementMessage(
            serverIdentifier.getServerId(),
            payload
        );
        messageBus.broadcast(ChannelConstants.REGISTRY_SLOT_FAMILY_ADVERTISEMENT, message);
        LOGGER.fine(() -> "Advertised slot families for " + serverIdentifier.getServerId() + ": " + payload);
    }

    /**
     * Handle a provision command from the registry.
     */
    public boolean handleProvisionCommand(SlotProvisionCommand command) {
        if (!active.get()) {
            LOGGER.fine("Ignoring provision command before activation");
            return false;
        }
        if (!serverIdentifier.getServerId().equals(command.getServerId())) {
            return false;
        }

        FamilyProfile profile = families.get(command.getFamily());
        if (profile == null) {
            LOGGER.warning(() -> "Provision command received for unsupported family " + command.getFamily());
            return false;
        }
        if (profile.slots.size() >= profile.maxSlots) {
            LOGGER.warning(() -> "Family " + profile.name + " at capacity (" + profile.maxSlots + ")" );
        }

        Map<String, String> metadata = new HashMap<>(command.getReadOnlyMetadata());
        metadata.putIfAbsent("requestId", command.getRequestId().toString());

        TrackedSlot slot = createSlot(profile, command.getVariant(), SlotLifecycleStatus.AVAILABLE, 0, metadata);
        if (slot == null) {
            return false;
        }
        broadcastSlotUpdate(slot);
        return true;
    }

    /**
     * Publish status snapshots for all active logical slots.
     */
    public void publishSnapshots() {
        if (!active.get()) {
            return;
        }
        families.values().forEach(profile -> profile.slots.values().forEach(this::broadcastSlotUpdate));
    }

    /**
     * Register a debug slot for a world that was manually loaded.
     */
    public String registerDebugSlot(String family,
                                    String variant,
                                    SlotLifecycleStatus status,
                                    int onlinePlayers,
                                    Map<String, String> metadata) {
        if (!active.get()) {
            LOGGER.warning("Ignoring debug slot registration before orchestrator activation");
            return null;
        }

        FamilyProfile profile = families.get(family);
        if (profile == null) {
            LOGGER.warning(() -> "Cannot register debug slot for unknown family " + family);
            return null;
        }

        if (profile.slots.size() >= profile.maxSlots) {
            LOGGER.warning(() -> "Debug slot registration exceeds capacity (" + profile.maxSlots + ") for family " + family);
        }

        TrackedSlot slot = createSlot(profile, variant, status, onlinePlayers, metadata);
        if (slot == null) {
            return null;
        }
        slot.metadata.putIfAbsent("debug", "true");
        broadcastSlotUpdate(slot);
        return slot.slotId;
    }

    /**
     * Update an existing slot's status and optional metadata.
     */
    public boolean updateSlotStatus(String slotId,
                                    SlotLifecycleStatus status,
                                    int onlinePlayers,
                                    Map<String, String> metadata) {
        TrackedSlot slot = findSlot(slotId);
        if (slot == null) {
            LOGGER.warning(() -> "Attempted to update unknown slot " + slotId);
            return false;
        }
        if (status != null) {
            slot.status = status;
        }
        slot.onlinePlayers = Math.max(0, onlinePlayers);
        if (metadata != null && !metadata.isEmpty()) {
            slot.metadata.putAll(metadata);
        }
        broadcastSlotUpdate(slot);
        return true;
    }

    /**
     * Remove a slot and broadcast a final status update.
     */
    public boolean removeSlot(String slotId,
                              SlotLifecycleStatus finalStatus,
                              Map<String, String> metadata) {
        TrackedSlot slot = findSlot(slotId);
        if (slot == null) {
            LOGGER.warning(() -> "Attempted to remove unknown slot " + slotId);
            return false;
        }

        if (finalStatus != null) {
            slot.status = finalStatus;
        }
        slot.onlinePlayers = 0;
        if (metadata != null && !metadata.isEmpty()) {
            slot.metadata.putAll(metadata);
        }
        broadcastSlotUpdate(slot);

        FamilyProfile profile = families.get(slot.family);
        if (profile != null) {
            profile.slots.remove(slot.suffix);
        }

        return true;
    }

    public Map<String, Integer> getFamilyCapacities() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        families.forEach((name, profile) -> snapshot.put(name, profile.maxSlots));
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Integer> getActiveSlotsByFamily() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        families.forEach((name, profile) -> snapshot.put(name, profile.slots.size()));
        return Collections.unmodifiableMap(snapshot);
    }

    public List<String> getActiveSlotSummaries() {
        List<String> summaries = new ArrayList<>();
        families.values().forEach(profile -> profile.slots.values().forEach(slot ->
            summaries.add(slot.slotId + "[" + slot.family + ":" + slot.status + "]")));
        return Collections.unmodifiableList(summaries);
    }

    private void broadcastSlotUpdate(TrackedSlot slot) {
        SlotStatusUpdateMessage message = new SlotStatusUpdateMessage(
            serverIdentifier.getServerId(),
            slot.slotId
        );
        message.setSlotSuffix(slot.suffix);
        message.setStatus(slot.status);
        message.setMaxPlayers(slot.maxPlayers);
        message.setOnlinePlayers(slot.onlinePlayers);
        message.setGameType(slot.variant != null && !slot.variant.isBlank() ? slot.variant : slot.family);
        Map<String, String> metadata = message.getMetadata();
        metadata.put("family", slot.family);
        metadata.put("environment", Objects.toString(environment, ""));
        if (slot.variant != null && !slot.variant.isBlank()) {
            metadata.put("variant", slot.variant);
        }
        metadata.putAll(slot.metadata);
        messageBus.broadcast(ChannelConstants.REGISTRY_SLOT_STATUS, message);
    }

    private static String generateSuffix(int index) {
        List<Character> chars = new ArrayList<>();
        int value = index;
        do {
            chars.add(0, (char) ('A' + (value % 26)));
            value = value / 26 - 1;
        } while (value >= 0);
        StringBuilder builder = new StringBuilder();
        chars.forEach(builder::append);
        return builder.toString();
    }

    private TrackedSlot createSlot(FamilyProfile profile,
                                   String variant,
                                   SlotLifecycleStatus status,
                                   int onlinePlayers,
                                   Map<String, String> metadata) {
        String suffix = generateSuffix(profile.counter.getAndIncrement());
        TrackedSlot slot = new TrackedSlot(serverIdentifier.getServerId() + suffix, suffix, profile.name);
        slot.variant = variant;
        slot.status = status != null ? status : SlotLifecycleStatus.AVAILABLE;
        slot.onlinePlayers = Math.max(0, onlinePlayers);
        slot.maxPlayers = Math.max(1, serverMaxPlayers / Math.max(1, profile.maxSlots));
        if (metadata != null && !metadata.isEmpty()) {
            slot.metadata.putAll(metadata);
        }
        profile.slots.put(slot.suffix, slot);
        return slot;
    }

    private TrackedSlot findSlot(String slotId) {
        for (FamilyProfile profile : families.values()) {
            for (TrackedSlot slot : profile.slots.values()) {
                if (slot.slotId.equals(slotId)) {
                    return slot;
                }
            }
        }
        return null;
    }

    private static class FamilyProfile {
        final String name;
        final int maxSlots;
        final AtomicInteger counter = new AtomicInteger(0);
        final Map<String, TrackedSlot> slots = new ConcurrentHashMap<>();

        FamilyProfile(String name, int maxSlots) {
            this.name = name;
            this.maxSlots = maxSlots;
        }
    }

    private static class TrackedSlot {
        final String slotId;
        final String suffix;
        final String family;
        String variant;
        SlotLifecycleStatus status = SlotLifecycleStatus.AVAILABLE;
        int maxPlayers;
        int onlinePlayers;
        final Map<String, String> metadata = new ConcurrentHashMap<>();

        TrackedSlot(String slotId, String suffix, String family) {
            this.slotId = slotId;
            this.suffix = suffix;
            this.family = family;
        }
    }
}
