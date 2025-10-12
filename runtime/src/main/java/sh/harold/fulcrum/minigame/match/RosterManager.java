package sh.harold.fulcrum.minigame.match;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks match participants, elimination, and respawn status.
 */
public final class RosterManager {
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public void addPlayer(UUID playerId, boolean respawnAllowed) {
        entries.putIfAbsent(playerId, new Entry(playerId, respawnAllowed));
    }

    public void removePlayer(UUID playerId) {
        entries.remove(playerId);
    }

    public Entry get(UUID playerId) {
        return entries.get(playerId);
    }

    public Collection<Entry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public long activeCount() {
        return entries.values().stream().filter(entry -> entry.getState() == PlayerState.ACTIVE).count();
    }

    public enum PlayerState {
        ACTIVE,
        ELIMINATED,
        SPECTATOR
    }

    public static final class Entry {
        private final UUID playerId;
        private volatile boolean respawnAllowed;
        private volatile PlayerState state;

        private Entry(UUID playerId, boolean respawnAllowed) {
            this.playerId = playerId;
            this.respawnAllowed = respawnAllowed;
            this.state = PlayerState.ACTIVE;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public boolean isRespawnAllowed() {
            return respawnAllowed;
        }

        public void setRespawnAllowed(boolean respawnAllowed) {
            this.respawnAllowed = respawnAllowed;
        }

        public PlayerState getState() {
            return state;
        }

        public void setState(PlayerState state) {
            this.state = state;
        }
    }
}
