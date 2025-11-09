package sh.harold.fulcrum.npc.poi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores NPC assignments keyed by POI anchor id.
 */
public final class PoiDescriptorRegistry {
    private final Map<String, List<PoiNpcAssignment>> assignments = new ConcurrentHashMap<>();

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public void register(PoiDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        for (PoiNpcAssignment assignment : descriptor.npcAssignments()) {
            register(assignment);
        }
    }

    public void register(PoiNpcAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        String key = normalize(assignment.poiAnchor());
        assignments.compute(key, (k, existing) -> {
            if (existing == null || existing.isEmpty()) {
                return List.of(assignment);
            }
            List<PoiNpcAssignment> copy = new java.util.ArrayList<>(existing);
            copy.add(assignment);
            return List.copyOf(copy);
        });
    }

    public List<PoiNpcAssignment> resolve(String poiAnchor) {
        if (poiAnchor == null) {
            return List.of();
        }
        return assignments.getOrDefault(normalize(poiAnchor), Collections.emptyList());
    }

    public int size() {
        return assignments.values().stream().mapToInt(List::size).sum();
    }
}
