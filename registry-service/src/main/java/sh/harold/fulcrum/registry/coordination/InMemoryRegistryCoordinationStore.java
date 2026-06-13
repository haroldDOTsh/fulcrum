package sh.harold.fulcrum.registry.coordination;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRegistryCoordinationStore implements RegistryCoordinationStore {
    private final Map<String, Map<String, CapacityLease>> leasesByScope = new ConcurrentHashMap<>();

    @Override
    public synchronized Optional<CapacityLease> reserveCapacity(
        String serverId,
        String familyId,
        int capacity,
        Duration ttl,
        Map<String, String> metadata
    ) {
        if (capacity <= 0) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        String scope = scope(serverId, familyId);
        Map<String, CapacityLease> scoped = leasesByScope.computeIfAbsent(scope, ignored -> new HashMap<>());
        removeExpired(scoped, now);
        if (scoped.size() >= capacity) {
            return Optional.empty();
        }

        long expiresAt = now + Math.max(1L, ttl.toMillis());
        CapacityLease lease = new CapacityLease(UUID.randomUUID().toString(), serverId, familyId, expiresAt, metadata);
        scoped.put(lease.ticketId(), lease);
        return Optional.of(lease);
    }

    @Override
    public synchronized void releaseCapacity(CapacityLease lease) {
        if (lease == null) {
            return;
        }
        Map<String, CapacityLease> scoped = leasesByScope.get(scope(lease.serverId(), lease.familyId()));
        if (scoped != null) {
            scoped.remove(lease.ticketId());
            if (scoped.isEmpty()) {
                leasesByScope.remove(scope(lease.serverId(), lease.familyId()));
            }
        }
    }

    private void removeExpired(Map<String, CapacityLease> leases, long now) {
        Iterator<CapacityLease> iterator = leases.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtEpochMillis() <= now) {
                iterator.remove();
            }
        }
    }

    private String scope(String serverId, String familyId) {
        return serverId + ":" + familyId;
    }
}
