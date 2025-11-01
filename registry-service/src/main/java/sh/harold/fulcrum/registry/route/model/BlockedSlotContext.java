package sh.harold.fulcrum.registry.route.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable view of the current slot and the set of blocked slots for a player request.
 */
public record BlockedSlotContext(String currentSlotId, Set<String> blockedSlotIds) {
    public BlockedSlotContext(String currentSlotId, Set<String> blockedSlotIds) {
        this.currentSlotId = currentSlotId;
        if (blockedSlotIds != null && !blockedSlotIds.isEmpty()) {
            this.blockedSlotIds = Collections.unmodifiableSet(new LinkedHashSet<>(blockedSlotIds));
        } else {
            this.blockedSlotIds = Collections.emptySet();
        }
    }
}
