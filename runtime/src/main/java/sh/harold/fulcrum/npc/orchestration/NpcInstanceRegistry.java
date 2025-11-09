package sh.harold.fulcrum.npc.orchestration;

import sh.harold.fulcrum.npc.adapter.NpcHandle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live NPC handles by their adapter identifiers for interaction routing.
 */
final class NpcInstanceRegistry {
    private final Map<UUID, NpcHandle> byAdapterId = new ConcurrentHashMap<>();
    private final Map<UUID, NpcHandle> byInstanceId = new ConcurrentHashMap<>();

    void register(NpcHandle handle) {
        byInstanceId.put(handle.instanceId(), handle);
        if (handle.adapterId() != null) {
            byAdapterId.put(handle.adapterId(), handle);
        }
    }

    void unregister(NpcHandle handle) {
        byInstanceId.remove(handle.instanceId());
        if (handle.adapterId() != null) {
            byAdapterId.remove(handle.adapterId());
        }
    }

    NpcHandle findByAdapterId(UUID adapterId) {
        return byAdapterId.get(adapterId);
    }

    NpcHandle findByInstanceId(UUID instanceId) {
        return byInstanceId.get(instanceId);
    }
}
