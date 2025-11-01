package sh.harold.fulcrum.registry.route.util;

import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.party.PartyConstants;
import sh.harold.fulcrum.registry.route.store.RedisRoutingStore;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.util.Map;

/**
 * Shared slot evaluation helpers used by the player routing service components.
 */
public final class SlotSelectionRules {
    private SlotSelectionRules() {
    }

    public static boolean isSlotEligible(LogicalSlotRecord slot) {
        if (slot == null) {
            return false;
        }
        SlotLifecycleStatus status = slot.getStatus();
        return status == SlotLifecycleStatus.AVAILABLE
                || status == SlotLifecycleStatus.ALLOCATED;
    }

    public static boolean variantMatches(LogicalSlotRecord slot, String variantId) {
        if (slot == null) {
            return false;
        }
        if (variantId == null || variantId.isBlank()) {
            return true;
        }
        Map<String, String> metadata = slot.getMetadata();
        String slotVariant = metadata.get("variant");
        if (variantId.equalsIgnoreCase(slotVariant)) {
            return true;
        }
        String slotGameType = slot.getGameType();
        if (variantId.equalsIgnoreCase(slotGameType)) {
            return true;
        }
        String metaVariant = metadata.get("familyVariant");
        return variantId.equalsIgnoreCase(metaVariant);
    }

    public static int resolveTeamCount(LogicalSlotRecord slot) {
        Map<String, String> metadata = slot.getMetadata();
        int teamCount = parsePositiveInt(metadata, "team.count");
        if (teamCount > 0) {
            return teamCount;
        }
        int teamSize = parsePositiveInt(metadata, "team.max");
        int maxPlayers = slot.getMaxPlayers();
        if (teamSize > 0 && maxPlayers > 0) {
            return Math.max(1, maxPlayers / Math.max(1, teamSize));
        }
        return teamSize > 0 ? Math.max(1, PartyConstants.HARD_SIZE_CAP / teamSize) : -1;
    }

    public static int parsePositiveInt(Map<String, String> metadata, String key) {
        if (metadata == null) {
            return -1;
        }
        String raw = metadata.get(key);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static int remainingCapacity(LogicalSlotRecord slot, RedisRoutingStore routingStore) {
        if (slot == null) {
            return 0;
        }
        int max = slot.getMaxPlayers();
        if (max <= 0) {
            return Integer.MAX_VALUE;
        }
        long pending = routingStore.getOccupancy(slot.getSlotId());
        int value = max - slot.getOnlinePlayers() - (int) pending;
        return Math.max(0, value);
    }
}
