package sh.harold.fulcrum.registry.route.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterCreatedMessage;
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterEndedMessage;
import sh.harold.fulcrum.registry.route.store.RedisRoutingStore;
import sh.harold.fulcrum.registry.route.util.SlotIdUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Handles match roster updates and keeps the active player tracker in sync.
 */
public final class MatchRosterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchRosterService.class);

    private final RedisRoutingStore routingStore;
    private final ActivePlayerTracker activePlayerTracker;

    public MatchRosterService(RedisRoutingStore routingStore, ActivePlayerTracker activePlayerTracker) {
        this.routingStore = routingStore;
        this.activePlayerTracker = activePlayerTracker;
    }

    public void handleCreated(MatchRosterCreatedMessage message) {
        if (message == null) {
            return;
        }
        String slotId = SlotIdUtils.sanitize(message.getSlotId());
        if (slotId == null) {
            return;
        }
        Set<UUID> players = message.getPlayers();
        if (players == null || players.isEmpty()) {
            routingStore.removeMatchRoster(slotId);
            activePlayerTracker.clearActivePlayersForSlot(slotId);
            return;
        }
        routingStore.storeMatchRoster(
                slotId,
                new RedisRoutingStore.MatchRosterEntry(message.getMatchId(), Set.copyOf(players), System.currentTimeMillis())
        );
        activePlayerTracker.recordActivePlayers(slotId, players);
    }

    public void handleEnded(MatchRosterEndedMessage message) {
        if (message == null) {
            return;
        }
        String slotId = SlotIdUtils.sanitize(message.getSlotId());
        if (slotId == null) {
            return;
        }
        Optional<RedisRoutingStore.MatchRosterEntry> snapshotOpt = routingStore.removeMatchRoster(slotId);
        if (snapshotOpt.isPresent()) {
            Set<UUID> players = snapshotOpt.get().getPlayers();
            if (players != null && !players.isEmpty()) {
                for (UUID playerId : players) {
                    if (playerId == null) {
                        continue;
                    }
                    activePlayerTracker.setActiveSlot(playerId, "")
                            .ifPresent(previous -> activePlayerTracker.rememberRecentSlot(playerId, previous));
                }
                return;
            }
        }
        activePlayerTracker.clearActivePlayersForSlot(slotId);
    }

    public void clearForSlot(String slotId) {
        String sanitized = SlotIdUtils.sanitize(slotId);
        if (sanitized == null) {
            return;
        }
        routingStore.removeMatchRoster(sanitized);
        activePlayerTracker.clearActivePlayersForSlot(sanitized);
    }
}
