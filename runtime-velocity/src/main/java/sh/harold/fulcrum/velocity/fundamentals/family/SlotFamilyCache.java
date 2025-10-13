package sh.harold.fulcrum.velocity.fundamentals.family;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Maintains a proxy-local view of slot families advertised by backends.
 * Data is aggregated from registry broadcasts so commands can understand
 * the families available on the network without querying each backend.
 */
public final class SlotFamilyCache {

    private final ConcurrentMap<String, Map<String, Integer>> serverFamilies = new ConcurrentHashMap<>();

    /**
     * Update the cached capacities for a server.
     *
     * @param serverId   unique backend identifier
     * @param capacities family -> available slot count map
     */
    public void update(String serverId, Map<String, Integer> capacities) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        if (capacities == null || capacities.isEmpty()) {
            serverFamilies.remove(serverId);
            return;
        }

        Map<String, Integer> snapshot = new LinkedHashMap<>();
        capacities.forEach((family, cap) -> {
            if (family == null || family.isBlank()) {
                return;
            }
            if (cap == null || cap <= 0) {
                return;
            }
            snapshot.put(family, cap);
        });

        if (snapshot.isEmpty()) {
            serverFamilies.remove(serverId);
            return;
        }

        serverFamilies.put(serverId, Collections.unmodifiableMap(snapshot));
    }

    /**
     * Remove cached data for a server that is going offline.
     *
     * @param serverId backend identifier
     */
    public void remove(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        serverFamilies.remove(serverId);
    }

    /**
     * @return immutable copy of per-server capacities.
     */
    public Map<String, Map<String, Integer>> snapshotPerServer() {
        return serverFamilies.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    /**
     * @return aggregated family availability across all servers.
     */
    public Map<String, Integer> aggregateCapacities() {
        Map<String, Integer> aggregate = new LinkedHashMap<>();
        serverFamilies.values().forEach(families ->
                families.forEach((family, cap) ->
                        aggregate.merge(family, cap, Integer::sum))
        );
        return aggregate;
    }

    /**
     * @return distinct family identifiers currently advertised.
     */
    public Set<String> families() {
        return serverFamilies.values().stream()
                .flatMap(map -> map.keySet().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Clear all cached data.
     */
    public void clear() {
        serverFamilies.clear();
    }
}
