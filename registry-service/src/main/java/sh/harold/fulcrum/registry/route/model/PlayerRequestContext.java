package sh.harold.fulcrum.registry.route.model;

import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.registry.route.util.SlotIdUtils;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Captures the state required to evaluate a player's routing request.
 */
public final class PlayerRequestContext {
    private final PlayerSlotRequest request;
    private final long createdAt;
    private final String currentSlotId;
    private final Set<String> blockedSlotIds;
    private final String variantId;
    private final String preferredSlotId;
    private final boolean rejoin;
    private volatile long lastEnqueuedAt;
    private int retries;

    public PlayerRequestContext(PlayerSlotRequest request,
                                BlockedSlotContext blockedContext,
                                String variantId,
                                String preferredSlotId,
                                boolean rejoin) {
        this(request, blockedContext, variantId, preferredSlotId, rejoin,
                System.currentTimeMillis(), System.currentTimeMillis(), 0);
    }

    public PlayerRequestContext(PlayerSlotRequest request,
                                BlockedSlotContext blockedContext,
                                String variantId,
                                String preferredSlotId,
                                boolean rejoin,
                                long createdAt,
                                long lastEnqueuedAt,
                                int retries) {
        this.request = request;
        this.createdAt = createdAt;
        this.currentSlotId = blockedContext != null ? blockedContext.currentSlotId() : null;
        Set<String> blocked = blockedContext != null ? blockedContext.blockedSlotIds() : Set.of();
        String normalizedPreferred = SlotIdUtils.normalize(preferredSlotId);
        if (normalizedPreferred != null && !blocked.isEmpty()) {
            Set<String> filtered = new LinkedHashSet<>(blocked);
            filtered.remove(normalizedPreferred);
            this.blockedSlotIds = Set.copyOf(filtered);
        } else {
            this.blockedSlotIds = Set.copyOf(blocked);
        }
        this.variantId = variantId;
        this.preferredSlotId = normalizedPreferred;
        this.rejoin = rejoin && normalizedPreferred != null;
        this.lastEnqueuedAt = lastEnqueuedAt;
        this.retries = retries;
    }

    public PlayerSlotRequest request() {
        return request;
    }

    public void markEnqueued() {
        lastEnqueuedAt = System.currentTimeMillis();
    }

    public boolean hasExceededWait(Duration threshold) {
        return System.currentTimeMillis() - createdAt >= threshold.toMillis();
    }

    public boolean registerRetry(int maxRetries) {
        retries++;
        return retries <= maxRetries;
    }

    public boolean isBlockedSlot(String slotId) {
        String normalized = SlotIdUtils.normalize(slotId);
        if (normalized == null) {
            return false;
        }
        if (rejoin && normalized.equalsIgnoreCase(preferredSlotId)) {
            return false;
        }
        return blockedSlotIds.contains(normalized);
    }

    public Set<String> blockedSlots() {
        return blockedSlotIds;
    }

    public String currentSlotId() {
        return currentSlotId;
    }

    public String variantId() {
        return variantId;
    }

    public boolean isRejoin() {
        return rejoin;
    }

    public String preferredSlotId() {
        return preferredSlotId;
    }

    public long createdAt() {
        return createdAt;
    }

    public long lastEnqueuedAt() {
        return lastEnqueuedAt;
    }

    public int retries() {
        return retries;
    }
}
