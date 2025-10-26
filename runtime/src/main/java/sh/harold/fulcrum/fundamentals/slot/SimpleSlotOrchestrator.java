package sh.harold.fulcrum.fundamentals.slot;

import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.SlotFamilyAdvertisementMessage;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.messagebus.messages.SlotProvisionCommand;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;
import sh.harold.fulcrum.api.slot.SlotFamilyDescriptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

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
    private final Set<String> disabledFamilies = ConcurrentHashMap.newKeySet();
    private volatile int serverSoftCap;
    private volatile int serverHardCap;
    private volatile String environment;
    private volatile String primaryFamily;

    public SimpleSlotOrchestrator(MessageBus messageBus, ServerIdentifier serverIdentifier) {
        this.messageBus = messageBus;
        this.serverIdentifier = serverIdentifier;
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

    private static String normalizeVariantId(String variant) {
        if (variant == null) {
            return null;
        }
        String trimmed = variant.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String buildGameId(String family, String variant) {
        String normalizedFamily = family != null ? family.toLowerCase(Locale.ROOT) : "unknown";
        if (variant == null || variant.isBlank()) {
            return normalizedFamily;
        }
        return normalizedFamily + "_" + variant.toLowerCase(Locale.ROOT);
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
        if (isFamilyDisabled(profile.name)) {
            LOGGER.warning(() -> "Provision command received for disabled family " + profile.name);
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
            String variantValue = metadata.get("variant");
            if (variantValue != null) {
                String normalizedVariant = normalizeVariantId(variantValue);
                if (normalizedVariant != null) {
                    slot.metadata.put("variant", normalizedVariant);
                    slot.variant = normalizedVariant;
                    FamilyProfile profile = families.get(slot.family);
                    if (profile != null) {
                        profile.trackVariant(normalizedVariant);
                    }
                } else {
                    slot.metadata.remove("variant");
                    slot.variant = null;
                }
            }
        }
        normalizeTeamMetadata(slot.metadata);
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
        normalizeTeamMetadata(slot.metadata);
        broadcastSlotUpdate(slot);

        FamilyProfile profile = families.get(slot.family);
        if (profile != null) {
            profile.slots.remove(slot.suffix);
        }

        return true;
    }

    /**
     * Broadcast the server's family capabilities to the registry.
     */
    public void advertiseFamilies() {
        if (!active.get() || families.isEmpty()) {
            return;
        }
        Map<String, Integer> payload = new LinkedHashMap<>();
        Map<String, List<String>> variantPayload = new LinkedHashMap<>();
        double availablePlayers = availablePlayerBudget();
        families.forEach((name, profile) -> {
            if (isFamilyDisabled(name)) {
                return;
            }
            payload.put(name, computeAvailableSlots(profile, availablePlayers));
            List<String> variants = profile.advertisedVariants();
            if (!variants.isEmpty()) {
                variantPayload.put(name, variants);
            }
        });
        SlotFamilyAdvertisementMessage message = new SlotFamilyAdvertisementMessage(
                serverIdentifier.getServerId(),
                payload
        );
        if (!variantPayload.isEmpty()) {
            message.setFamilyVariants(variantPayload);
        }
        messageBus.broadcast(ChannelConstants.REGISTRY_SLOT_FAMILY_ADVERTISEMENT, message);
        LOGGER.fine(() -> "Advertised slot families for " + serverIdentifier.getServerId() + ": " + payload);
    }

    public Map<String, Integer> getFamilyCapacities() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        double availablePlayers = availablePlayerBudget();
        families.forEach((name, profile) -> {
            if (!isFamilyDisabled(name)) {
                snapshot.put(name, computeAvailableSlots(profile, availablePlayers));
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }

    public List<String> getActiveSlotSummaries() {
        List<String> summaries = new ArrayList<>();
        families.values().forEach(profile -> profile.slots.values().forEach(slot ->
                summaries.add(slot.slotId + "[" + slot.family + ":" + slot.status + "]")));
        return Collections.unmodifiableList(summaries);
    }

    private void broadcastSlotUpdate(TrackedSlot slot) {
        normalizeTeamMetadata(slot.metadata);
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
            String variantValue = metadata.get("variant");
            metadata.forEach((key, value) -> {
                if (value == null) {
                    slot.metadata.remove(key);
                } else {
                    slot.metadata.put(key, value);
                }
            });
            if (variantValue != null) {
                String normalizedVariant = normalizeVariantId(variantValue);
                if (normalizedVariant != null) {
                    slot.metadata.put("variant", normalizedVariant);
                    slot.variant = normalizedVariant;
                    FamilyProfile profile = families.get(slot.family);
                    if (profile != null) {
                        profile.trackVariant(normalizedVariant);
                    }
                } else {
                    slot.metadata.remove("variant");
                    slot.variant = null;
                }
            }
        }
        broadcastSlotUpdate(slot);
        return true;
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
        final AtomicInteger counter = new AtomicInteger(0);
        final Map<String, TrackedSlot> slots = new ConcurrentHashMap<>();
        final CopyOnWriteArraySet<String> declaredVariants = new CopyOnWriteArraySet<>();
        volatile SlotFamilyDescriptor descriptor;

        FamilyProfile(SlotFamilyDescriptor descriptor) {
            this.name = descriptor.getFamilyId();
            this.descriptor = descriptor;
            refreshDeclaredVariants(descriptor);
        }

        void updateDescriptor(SlotFamilyDescriptor descriptor) {
            this.descriptor = descriptor;
            refreshDeclaredVariants(descriptor);
        }

        void trackVariant(String variant) {
            String normalized = normalizeVariantId(variant);
            if (normalized != null) {
                declaredVariants.add(normalized);
            }
        }

        List<String> advertisedVariants() {
            LinkedHashSet<String> variants = new LinkedHashSet<>(declaredVariants);
            for (TrackedSlot slot : slots.values()) {
                addVariant(variants, slot.variant);
                addVariant(variants, slot.metadata.get("variant"));
            }
            return List.copyOf(variants);
        }

        private void refreshDeclaredVariants(SlotFamilyDescriptor descriptor) {
            declaredVariants.clear();
            if (descriptor == null) {
                return;
            }
            Map<String, String> metadata = descriptor.getMetadata();
            if (metadata == null || metadata.isEmpty()) {
                return;
            }
            trackVariant(metadata.get("variant"));
            trackVariant(metadata.get("defaultVariant"));
            String variants = metadata.get("variants");
            if (variants != null) {
                for (String token : variants.split("[,;\\s]+")) {
                    trackVariant(token);
                }
            }
        }

        private void addVariant(Collection<String> collection, String candidate) {
            String normalized = normalizeVariantId(candidate);
            if (normalized != null) {
                collection.add(normalized);
            }
        }
    }

    private static class TrackedSlot {
        final String slotId;
        final String suffix;
        final String family;
        final SlotFamilyDescriptor descriptor;
        final Map<String, String> metadata = new ConcurrentHashMap<>();
        String variant;
        SlotLifecycleStatus status = SlotLifecycleStatus.AVAILABLE;
        int maxPlayers;
        int onlinePlayers;

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

    private void normalizeTeamMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        sanitizeTeamEntry(metadata, "team.max");
        sanitizeTeamEntry(metadata, "team.count");
        metadata.keySet().removeIf(key -> key.startsWith("team.") && !key.equals("team.max") && !key.equals("team.count"));
    }

    private void sanitizeTeamEntry(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            metadata.remove(key);
        } else {
            metadata.put(key, trimmed);
        }
    }

    public Map<String, Integer> getActiveSlotsByFamily() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        families.forEach((name, profile) -> {
            if (!isFamilyDisabled(name)) {
                snapshot.put(name, profile.slots.size());
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }

    public void disableFamily(String familyId, String reason) {
        String key = normalizeFamilyId(familyId);
        if (key == null) {
            return;
        }
        if (disabledFamilies.add(key)) {
            if (reason != null && !reason.isBlank()) {
                LOGGER.warning(() -> "Disabling slot family " + key + " (" + reason + ")");
            } else {
                LOGGER.warning(() -> "Disabling slot family " + key);
            }
            advertiseFamilies();
        }
    }

    public void enableFamily(String familyId) {
        String key = normalizeFamilyId(familyId);
        if (key == null) {
            return;
        }
        if (disabledFamilies.remove(key)) {
            LOGGER.info(() -> "Re-enabling slot family " + key);
            advertiseFamilies();
        }
    }

    private boolean isFamilyDisabled(String familyId) {
        String key = normalizeFamilyId(familyId);
        return key != null && disabledFamilies.contains(key);
    }

    private String normalizeFamilyId(String familyId) {
        if (familyId == null) {
            return null;
        }
        String trimmed = familyId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private TrackedSlot createSlot(FamilyProfile profile,
                                   String variant,
                                   SlotLifecycleStatus status,
                                   int onlinePlayers,
                                   Map<String, String> metadata) {
        if (profile == null || isFamilyDisabled(profile.name)) {
            if (profile != null) {
                LOGGER.warning(() -> "Skipping slot creation for disabled family " + profile.name);
            }
            return null;
        }
        String suffix = generateSuffix(profile.counter.getAndIncrement());
        SlotFamilyDescriptor descriptor = profile.descriptor;
        TrackedSlot slot = new TrackedSlot(serverIdentifier.getServerId() + suffix, suffix, profile.name, descriptor);
        String normalizedVariant = normalizeVariantId(variant);
        slot.variant = normalizedVariant;
        slot.status = status != null ? status : SlotLifecycleStatus.AVAILABLE;
        slot.onlinePlayers = Math.max(0, onlinePlayers);
        int maxPlayers = resolveMaxPlayers(descriptor);
        slot.maxPlayers = maxPlayers;
        if (metadata != null && !metadata.isEmpty()) {
            slot.metadata.putAll(metadata);
        }
        Map<String, String> descriptorMetadata = descriptor.getMetadata();
        if (descriptorMetadata != null && !descriptorMetadata.isEmpty()) {
            descriptorMetadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    slot.metadata.putIfAbsent(key, value);
                }
            });
        }
        slot.metadata.putIfAbsent("family", profile.name);
        if (normalizedVariant != null) {
            slot.metadata.put("variant", normalizedVariant);
            profile.trackVariant(normalizedVariant);
        } else {
            String metaVariant = slot.metadata.get("variant");
            String normalizedMetaVariant = normalizeVariantId(metaVariant);
            if (normalizedMetaVariant != null) {
                slot.metadata.put("variant", normalizedMetaVariant);
                slot.variant = normalizedMetaVariant;
                profile.trackVariant(normalizedMetaVariant);
            } else {
                slot.metadata.remove("variant");
                slot.variant = null;
            }
        }
        slot.metadata.putIfAbsent("gameId", buildGameId(profile.name, slot.variant != null ? slot.variant : slot.metadata.get("variant")));
        slot.metadata.putIfAbsent("familyMinPlayers", String.valueOf(descriptor.getMinPlayers()));
        slot.metadata.putIfAbsent("familyMaxPlayers", String.valueOf(maxPlayers));
        slot.metadata.putIfAbsent("playerEquivalentFactor", String.valueOf(descriptor.getPlayerEquivalentFactor()));
        normalizeTeamMetadata(slot.metadata);
        profile.slots.put(slot.suffix, slot);
        return slot;
    }
}
