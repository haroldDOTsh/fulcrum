package sh.harold.fulcrum.registry.route.service;

import sh.harold.fulcrum.registry.route.store.RedisRoutingStore;
import sh.harold.fulcrum.registry.route.util.SlotIdUtils;

import java.time.Duration;
import java.util.*;

/**
 * Tracks player-to-slot assignments and recent history using Redis.
 */
public final class ActivePlayerTracker {

    private final RedisRoutingStore routingStore;
    private final Duration recentSlotTtl;
    private final int recentSlotHistory;

    public ActivePlayerTracker(RedisRoutingStore routingStore,
                               Duration recentSlotTtl,
                               int recentSlotHistory) {
        this.routingStore = routingStore;
        this.recentSlotTtl = recentSlotTtl;
        this.recentSlotHistory = recentSlotHistory;
    }

    public Optional<String> setActiveSlot(UUID playerId, String slotId) {
        return routingStore.setActiveSlot(playerId, SlotIdUtils.sanitize(slotId));
    }

    public Optional<String> getActiveSlot(UUID playerId) {
        return routingStore.getActiveSlot(playerId);
    }

    public Set<UUID> clearActivePlayersForSlot(String slotId) {
        Set<UUID> cleared = routingStore.removeActivePlayersForSlot(SlotIdUtils.sanitize(slotId));
        long now = System.currentTimeMillis();
        for (UUID playerId : cleared) {
            routingStore.pushRecentSlot(playerId, slotId, now);
        }
        return cleared;
    }

    public void recordActivePlayers(String slotId, Set<UUID> players) {
        String sanitizedSlotId = SlotIdUtils.sanitize(slotId);
        if (sanitizedSlotId == null || players == null || players.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (UUID playerId : players) {
            if (playerId == null) {
                continue;
            }
            routingStore.setActiveSlot(playerId, sanitizedSlotId)
                    .ifPresent(previous -> {
                        String normalized = SlotIdUtils.sanitize(previous);
                        if (normalized != null && !normalized.equalsIgnoreCase(sanitizedSlotId)) {
                            routingStore.pushRecentSlot(playerId, normalized, now);
                        }
                    });
        }
    }

    public void rememberRecentSlot(UUID playerId, String slotId) {
        String sanitizedSlotId = SlotIdUtils.sanitize(slotId);
        if (playerId == null || sanitizedSlotId == null) {
            return;
        }
        routingStore.pushRecentSlot(playerId, sanitizedSlotId, System.currentTimeMillis());
    }

    public Set<String> resolveRecentBlockedSlots(UUID playerId) {
        if (playerId == null) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        List<String> recentSlots = routingStore.getRecentSlots(playerId, now);
        if (recentSlots.isEmpty()) {
            return Set.of();
        }
        Set<String> blocked = new HashSet<>();
        for (String slot : recentSlots) {
            String normalized = SlotIdUtils.normalize(slot);
            if (normalized != null) {
                blocked.add(normalized);
            }
        }
        routingStore.trimRecentSlots(playerId);
        return blocked;
    }
}
