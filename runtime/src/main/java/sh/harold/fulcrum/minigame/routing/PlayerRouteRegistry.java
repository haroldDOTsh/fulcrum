package sh.harold.fulcrum.minigame.routing;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks routing assignments received from the proxy so states can reason about slot ownership.
 */
public final class PlayerRouteRegistry {
    private final Map<UUID, RouteAssignment> assignments = new ConcurrentHashMap<>();

    public void register(RouteAssignment assignment) {
        if (assignment == null) {
            return;
        }
        Map<String, String> metadata = assignment.metadata() != null
            ? Map.copyOf(assignment.metadata())
            : Map.of();
        RouteAssignment copy = new RouteAssignment(
            assignment.playerId(),
            assignment.playerName(),
            assignment.slotId(),
            assignment.familyId(),
            assignment.variant(),
            assignment.proxyId(),
            assignment.targetWorld(),
            metadata
        );
        assignments.put(copy.playerId(), copy);
    }

    public Optional<RouteAssignment> get(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(assignments.get(playerId));
    }

    public void remove(UUID playerId) {
        if (playerId == null) {
            return;
        }
        assignments.remove(playerId);
    }

    public Collection<RouteAssignment> values() {
        return assignments.values();
    }

    public record RouteAssignment(UUID playerId,
                                  String playerName,
                                  String slotId,
                                  String familyId,
                                  String variant,
                                  String proxyId,
                                  String targetWorld,
                                  Map<String, String> metadata) {
    }
}
