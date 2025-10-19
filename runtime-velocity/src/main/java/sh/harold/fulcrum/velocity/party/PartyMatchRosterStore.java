package sh.harold.fulcrum.velocity.party;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks which players a party match instance will accept for follow-up warps.
 * Entries expire automatically after a short duration so stale matches do not linger.
 */
final class PartyMatchRosterStore {
    private static final long DEFAULT_TTL_MILLIS = Duration.ofMinutes(15).toMillis();

    private final ConcurrentMap<String, Entry> rosters = new ConcurrentHashMap<>();
    private final long ttlMillis;

    PartyMatchRosterStore() {
        this(DEFAULT_TTL_MILLIS);
    }

    PartyMatchRosterStore(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        this.ttlMillis = ttlMillis;
    }

    void registerRoster(String slotId, UUID matchId, Set<UUID> participants) {
        if (slotId == null || slotId.isBlank()) {
            return;
        }
        if (participants == null || participants.isEmpty()) {
            rosters.remove(slotId);
            return;
        }

        long expiresAt = System.currentTimeMillis() + ttlMillis;
        Set<UUID> snapshot = Set.copyOf(participants);
        rosters.put(slotId, new Entry(matchId, snapshot, expiresAt));
    }

    Optional<RosterSnapshot> getRoster(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Optional.empty();
        }

        Entry entry = rosters.get(slotId);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry)) {
            rosters.remove(slotId, entry);
            return Optional.empty();
        }
        return Optional.of(new RosterSnapshot(entry.matchId, entry.players, entry.expiresAt));
    }

    void purgeExpired() {
        if (rosters.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> mapEntry : rosters.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (entry.expiresAt <= now) {
                rosters.remove(mapEntry.getKey(), entry);
            }
        }
    }

    private boolean isExpired(Entry entry) {
        return entry.expiresAt <= System.currentTimeMillis();
    }

    private record Entry(UUID matchId, Set<UUID> players, long expiresAt) {
            private Entry(UUID matchId, Set<UUID> players, long expiresAt) {
                this.matchId = matchId;
                this.players = Collections.unmodifiableSet(players);
                this.expiresAt = expiresAt;
            }
        }

    record RosterSnapshot(UUID matchId, Set<UUID> players, long expiresAt) {
    }
}
