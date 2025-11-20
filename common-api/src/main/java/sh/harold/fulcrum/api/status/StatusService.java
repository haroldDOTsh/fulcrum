package sh.harold.fulcrum.api.status;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Shared status API for reading and mutating player presence.
 */
public interface StatusService {

    CompletionStage<PlayerStatus> getStatus(UUID playerId);

    CompletionStage<Map<UUID, PlayerStatus>> getStatuses(Collection<UUID> playerIds);

    CompletionStage<Void> updateStatus(UUID playerId, PresenceStatus presence, String activityBadge);
}

