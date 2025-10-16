package sh.harold.fulcrum.velocity.fundamentals.family;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * Maintains a proxy-local view of slot families and their variants
 * as advertised by backend servers. Data is aggregated from registry
 * broadcasts so proxy commands can reason about playable options.
 */
public final class SlotFamilyCache {

    private final ConcurrentMap<String, ConcurrentMap<String, FamilyData>> serverFamilies = new ConcurrentHashMap<>();

    /**
     * Update capacities for the given server. Families not present in the payload
     * are removed from the cache for that server.
     */
    public void updateCapacities(String serverId, Map<String, Integer> capacities) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        if (capacities == null) {
            serverFamilies.remove(serverId);
            return;
        }

        ConcurrentMap<String, FamilyData> families = serverFamilies.computeIfAbsent(serverId, key -> new ConcurrentHashMap<>());
        Set<String> normalisedKeys = capacities.keySet().stream()
                .map(this::normalize)
                .collect(Collectors.toSet());
        families.keySet().removeIf(family -> !normalisedKeys.contains(family));

        capacities.forEach((family, cap) -> {
            String key = normalize(family);
            if (key == null) {
                return;
            }
            int normalised = cap == null ? 0 : Math.max(0, cap);
            FamilyData data = families.computeIfAbsent(key, ignored -> new FamilyData());
            data.capacity = normalised;
        });

        if (families.isEmpty()) {
            serverFamilies.remove(serverId);
        }
    }

    /**
     * Update advertised variants for the given server.
     */
    public void updateVariants(String serverId, Map<String, ? extends Collection<String>> variants) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        if (variants == null) {
            ConcurrentMap<String, FamilyData> families = serverFamilies.get(serverId);
            if (families != null) {
                families.values().forEach(data -> data.variants.clear());
            }
            return;
        }

        ConcurrentMap<String, FamilyData> families = serverFamilies.computeIfAbsent(serverId, key -> new ConcurrentHashMap<>());
        Set<String> normalisedKeys = variants.keySet().stream()
                .map(this::normalize)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        families.forEach((family, data) -> {
            if (!normalisedKeys.contains(family)) {
                data.variants.clear();
            }
        });

        variants.forEach((family, values) -> {
            String key = normalize(family);
            if (key == null) {
                return;
            }
            FamilyData data = families.computeIfAbsent(key, ignored -> new FamilyData());
            data.variants.clear();
            if (values == null) {
                return;
            }
            for (String variant : values) {
                String variantKey = normalize(variant);
                if (variantKey != null) {
                    data.variants.add(variantKey);
                }
            }
        });
    }

    /**
     * Record that a variant is advertised for a family on a server.
     */
    public void recordVariant(String serverId, String familyId, String variantId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        String familyKey = normalize(familyId);
        if (familyKey == null) {
            return;
        }
        String variantKey = normalize(variantId);
        if (variantKey == null) {
            return;
        }
        ConcurrentMap<String, FamilyData> families = serverFamilies.computeIfAbsent(serverId, key -> new ConcurrentHashMap<>());
        FamilyData data = families.computeIfAbsent(familyKey, key -> new FamilyData());
        data.variants.add(variantKey);
    }

    /**
     * Remove cache entry for a server that went offline.
     */
    public void remove(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        serverFamilies.remove(serverId);
    }

    /**
     * Snapshot of server -> family -> capacity data.
     */
    public Map<String, Map<String, Integer>> snapshotPerServer() {
        Map<String, Map<String, Integer>> snapshot = new LinkedHashMap<>();
        serverFamilies.forEach((serverId, families) -> {
            Map<String, Integer> capacities = families.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().capacity,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
            if (!capacities.isEmpty()) {
                snapshot.put(serverId, Collections.unmodifiableMap(capacities));
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Aggregate family capacities across all servers.
     */
    public Map<String, Integer> aggregateCapacities() {
        Map<String, Integer> aggregate = new LinkedHashMap<>();
        serverFamilies.values().forEach(families ->
                families.forEach((family, data) ->
                        aggregate.merge(family, data.capacity, Integer::sum)));
        return Collections.unmodifiableMap(aggregate);
    }

    /**
     * All known families.
     */
    public Set<String> families() {
        return serverFamilies.values().stream()
                .flatMap(map -> map.keySet().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Known variants for the given family.
     */
    public Set<String> variants(String familyId) {
        String key = normalize(familyId);
        if (key == null) {
            return Set.of();
        }
        Set<String> variants = new LinkedHashSet<>();
        serverFamilies.values().forEach(families -> {
            FamilyData data = families.get(key);
            if (data != null && !data.variants.isEmpty()) {
                variants.addAll(data.variants);
            }
        });
        return Collections.unmodifiableSet(variants);
    }

    /**
     * Whether the family is known to the cache.
     */
    public boolean hasFamily(String familyId) {
        String key = normalize(familyId);
        if (key == null) {
            return false;
        }
        return serverFamilies.values().stream().anyMatch(families -> families.containsKey(key));
    }

    /**
     * Clear the cache.
     */
    public void clear() {
        serverFamilies.clear();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static final class FamilyData {
        final Set<String> variants = new CopyOnWriteArraySet<>();
        volatile int capacity;
    }
}
