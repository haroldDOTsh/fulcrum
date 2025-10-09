package sh.harold.fulcrum.fundamentals.slot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;

/**
 * Pragmatic orchestration layer that advertises slot families and mints logical slots on demand.
 */
public class SimpleSlotOrchestrator {
    private static final Logger LOGGER = Logger.getLogger(SimpleSlotOrchestrator.class.getName());

    private final MessageBus messageBus;
    private final ServerIdentifier serverIdentifier;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Consumer<ProvisionedSlot>> provisionListeners = new CopyOnWriteArrayList<>();

    private final Map<String, FamilyProfile> families = new ConcurrentHashMap<>();
    private volatile int serverSoftCap;
    private volatile int serverHardCap;
    private volatile String environment;
    private volatile String primaryFamily;

    public SimpleSlotOrchestrator(MessageBus messageBus, ServerIdentifier serverIdentifier) {
        this.messageBus = messageBus;
        this.serverIdentifier = serverIdentifier;
    }

    private double computeUsedPlayerBudget() {
        double used = 0.0;
        for (FamilyProfile profile : families.values()) {
            used += profile.slots.size() * resolvePlayerCost(profile.descriptor);
        }
        return used;
    }

    private double availablePlayerBudget() {
        int budget = serverSoftCap > 0 ? serverSoftCap : serverHardCap;
        if (budget <= 0) {
            return 0.0;
        }
        return Math.max(0.0, budget - computeUsedPlayerBudget());
    }

    private int computeAvailableSlots(FamilyProfile profile, double availablePlayers) {
        double cost = resolvePlayerCost(profile.descriptor);
        if (cost <= 0.0) {
            return 0;
        }
        return (int) Math.floor(availablePlayers / cost);
    }

    private double resolvePlayerCost(SlotFamilyDescriptor descriptor) {
        double factor = descriptor.getPlayerEquivalentFactor() / 10.0;
        int maxPlayers = resolveMaxPlayers(descriptor);
        return maxPlayers * factor;
    }

    private int resolveMaxPlayers(FamilyProfile profile) {
        return resolveMaxPlayers(profile.descriptor);
    }

    private int resolveMaxPlayers(SlotFamilyDescriptor descriptor) {
        int maxPlayers = descriptor.getMaxPlayers();
        if (maxPlayers > 0) {
            return maxPlayers;
        }
        int fallback = serverSoftCap > 0 ? serverSoftCap : serverHardCap;
        return Math.max(1, fallback);
    }

    private void validateActiveFamilies() {
        if (families.isEmpty()) {
            return;
        }
        if (serverSoftCap <= 0 && serverHardCap <= 0) {
            return;
        }
        families.values().forEach(profile -> logDescriptorWarnings(profile.descriptor));
    }

    private void logDescriptorWarnings(SlotFamilyDescriptor descriptor) {
        int hardCap = serverHardCap > 0 ? serverHardCap : serverSoftCap;
        int maxPlayers = resolveMaxPlayers(descriptor);
        if (hardCap > 0 && maxPlayers > hardCap) {
            LOGGER.warning(() -> "Family " + descriptor.getFamilyId() + " declares maxPlayers=" + maxPlayers
                + " exceeding server hard cap " + hardCap + " (docs/slot-family-discovery-notes.md)");
        }

        double playerCost = resolvePlayerCost(descriptor);
        if (hardCap > 0 && playerCost > hardCap) {
            LOGGER.warning(() -> "Family " + descriptor.getFamilyId() + " consumes "
                + String.format("%.1f", playerCost) + " player budget which exceeds hard cap " + hardCap
                + " (docs/slot-family-discovery-notes.md)");
        }
    }

    /**
     * Configure the slot families the server can host using module descriptors
     * (docs/slot-family-discovery-notes.md: Module-led discovery).
     */
    public synchronized void configureFamilies(Collection<SlotFamilyDescriptor> descriptors) {
        Map<String, FamilyProfile> nextProfiles = new LinkedHashMap<>();
        if (descriptors != null) {
            for (SlotFamilyDescriptor descriptor : descriptors) {
                if (descriptor == null) {
                    continue;
                }
                String familyId = descriptor.getFamilyId();
                if (familyId == null || familyId.isBlank()) {
                    continue;
                }
                FamilyProfile profile = families.remove(familyId);
                if (profile == null) {
                    profile = new FamilyProfile(descriptor);
                } else {
                    profile.updateDescriptor(descriptor);
                }
                nextProfiles.put(familyId, profile);
            }
        }

        // Families left in the map were removed; drop their slots.
        if (!families.isEmpty()) {
            families.values().forEach(profile -> {
                if (!profile.slots.isEmpty()) {
                    LOGGER.info(() -> "Clearing " + profile.slots.size() + " slots for retired family " + profile.name);
                }
                profile.slots.clear();
            });
        }

        families.clear();
        families.putAll(nextProfiles);
        primaryFamily = families.keySet().stream().findFirst().orElse(primaryFamily);

        validateActiveFamilies();

        if (active.get()) {
            advertiseFamilies();
        }
    }

    /**
     * Called once the backend has a permanent ID and is ready to advertise.
     */
    public void onServerRegistered(String fallbackFamily,
                                   String environment,
                                   int softCap,
                                   int hardCap) {
        this.environment = environment;
        this.serverSoftCap = softCap;
        this.serverHardCap = hardCap;
        primaryFamily = families.keySet().stream().findFirst().orElse(fallbackFamily);
        active.set(true);
        validateActiveFamilies();
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
        double availablePlayers = availablePlayerBudget();
        families.forEach((name, profile) -> payload.put(name, computeAvailableSlots(profile, availablePlayers)));
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
        double currentUsed = computeUsedPlayerBudget();
        double playerCost = resolvePlayerCost(profile.descriptor);
        double projected = currentUsed + playerCost;

        if (serverHardCap > 0 && projected > serverHardCap) {
            LOGGER.warning(() -> "Refusing provision for " + profile.name + " because projected budget "
                + String.format("%.1f", projected) + " exceeds hard cap " + serverHardCap);
            return false;
        }
        if (serverSoftCap > 0 && projected > serverSoftCap) {
            LOGGER.warning(() -> "Provision for " + profile.name + " exceeds soft cap " + serverSoftCap
                + " (projected=" + String.format("%.1f", projected) + ")");
        }

        double availablePlayers = Math.max(0.0, (serverSoftCap > 0 ? serverSoftCap : serverHardCap) - currentUsed);
        int availableSlots = computeAvailableSlots(profile, availablePlayers);
        if (availableSlots <= 0) {
            LOGGER.warning(() -> "Family " + profile.name + " has no remaining budget (availablePlayers="
                + String.format("%.1f", availablePlayers) + ")");
        }

        Map<String, String> metadata = new HashMap<>(command.getReadOnlyMetadata());
        metadata.putIfAbsent("requestId", command.getRequestId().toString());
        metadata.putIfAbsent("family", profile.name);
        if (command.getVariant() != null && !command.getVariant().isBlank()) {
            metadata.putIfAbsent("variant", command.getVariant());
        }

        TrackedSlot slot = createSlot(profile, command.getVariant(), SlotLifecycleStatus.PROVISIONING, 0, metadata);
        if (slot == null) {
            return false;
        }

        broadcastSlotUpdate(slot);
        notifyProvisioned(slot);
        return true;
    }

    public void addProvisionListener(Consumer<ProvisionedSlot> listener) {
        if (listener == null) {
            return;
        }
        provisionListeners.addIfAbsent(listener);
    }

    private void notifyProvisioned(TrackedSlot slot) {
        if (provisionListeners.isEmpty()) {
            return;
        }
        Map<String, String> metadataSnapshot = new HashMap<>(slot.metadata);
        ProvisionedSlot snapshot = new ProvisionedSlot(
            slot.slotId,
            slot.family,
            slot.variant,
            Collections.unmodifiableMap(metadataSnapshot)
        );
        provisionListeners.forEach(listener -> {
            try {
                listener.accept(snapshot);
            } catch (Exception ex) {
                LOGGER.warning(() -> "Provision listener threw exception: " + ex.getMessage());
            }
        });
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

        double availablePlayers = availablePlayerBudget();
        int availableSlots = computeAvailableSlots(profile, availablePlayers);
        if (availableSlots <= 0) {
            LOGGER.warning(() -> "Debug slot registration exceeds budget for family " + family);
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
     * Update metadata for an existing slot without altering lifecycle state.
     */
    public boolean updateSlotMetadata(String slotId, Map<String, String> metadata) {
        TrackedSlot slot = findSlot(slotId);
        if (slot == null) {
            LOGGER.warning(() -> "Attempted to update metadata for unknown slot " + slotId);
            return false;
        }
        if (metadata != null && !metadata.isEmpty()) {
            metadata.forEach((key, value) -> {
                if (value == null) {
                    slot.metadata.remove(key);
                } else {
                    slot.metadata.put(key, value);
                }
            });
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
        double availablePlayers = availablePlayerBudget();
        families.forEach((name, profile) -> snapshot.put(name, computeAvailableSlots(profile, availablePlayers)));
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
        SlotFamilyDescriptor descriptor = profile.descriptor;
        TrackedSlot slot = new TrackedSlot(serverIdentifier.getServerId() + suffix, suffix, profile.name, descriptor);
        slot.variant = variant;
        slot.status = status != null ? status : SlotLifecycleStatus.AVAILABLE;
        slot.onlinePlayers = Math.max(0, onlinePlayers);
        int maxPlayers = resolveMaxPlayers(descriptor);
        slot.maxPlayers = maxPlayers;
        if (metadata != null && !metadata.isEmpty()) {
            slot.metadata.putAll(metadata);
        }
        slot.metadata.putIfAbsent("family", profile.name);
        if (variant != null && !variant.isBlank()) {
            slot.metadata.put("variant", variant);
        }
        slot.metadata.putIfAbsent("familyMinPlayers", String.valueOf(descriptor.getMinPlayers()));
        slot.metadata.putIfAbsent("familyMaxPlayers", String.valueOf(maxPlayers));
        slot.metadata.putIfAbsent("playerEquivalentFactor", String.valueOf(descriptor.getPlayerEquivalentFactor()));
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
        volatile SlotFamilyDescriptor descriptor;
        final AtomicInteger counter = new AtomicInteger(0);
        final Map<String, TrackedSlot> slots = new ConcurrentHashMap<>();

        FamilyProfile(SlotFamilyDescriptor descriptor) {
            this.name = descriptor.getFamilyId();
            this.descriptor = descriptor;
        }

        void updateDescriptor(SlotFamilyDescriptor descriptor) {
            this.descriptor = descriptor;
        }
    }

    private static class TrackedSlot {
        final String slotId;
        final String suffix;
        final String family;
        final SlotFamilyDescriptor descriptor;
        String variant;
        SlotLifecycleStatus status = SlotLifecycleStatus.AVAILABLE;
        int maxPlayers;
        int onlinePlayers;
        final Map<String, String> metadata = new ConcurrentHashMap<>();

        TrackedSlot(String slotId, String suffix, String family, SlotFamilyDescriptor descriptor) {
            this.slotId = slotId;
            this.suffix = suffix;
            this.family = family;
            this.descriptor = descriptor;
        }
    }

    public record ProvisionedSlot(String slotId,
                                  String familyId,
                                  String variant,
                                  Map<String, String> metadata) {
    }

}


