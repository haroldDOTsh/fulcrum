package sh.harold.fulcrum.api.data.dirty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class DirtyDataEntry {

    private final UUID playerId;
    private final String schemaKey;
    private final Object data;
    private final Instant timestamp;
    private final ChangeType changeType;

    /**
     * Creates a new dirty data entry.
     *
     * @param playerId   The ID of the player whose data was modified
     * @param schemaKey  The schema key identifying the type of data
     * @param data       The actual data object that was modified
     * @param changeType The type of change that occurred
     */
    public DirtyDataEntry(UUID playerId, String schemaKey, Object data, ChangeType changeType) {
        this.playerId = Objects.requireNonNull(playerId, "playerId cannot be null");
        this.schemaKey = Objects.requireNonNull(schemaKey, "schemaKey cannot be null");
        this.data = data; // Can be null for DELETE operations
        this.timestamp = Instant.now();
        this.changeType = Objects.requireNonNull(changeType, "changeType cannot be null");
    }

    /**
     * Creates a new dirty data entry with a specific timestamp.
     *
     * @param playerId   The ID of the player whose data was modified
     * @param schemaKey  The schema key identifying the type of data
     * @param data       The actual data object that was modified
     * @param changeType The type of change that occurred
     * @param timestamp  The timestamp when the change occurred
     */
    public DirtyDataEntry(UUID playerId, String schemaKey, Object data, ChangeType changeType, Instant timestamp) {
        this.playerId = Objects.requireNonNull(playerId, "playerId cannot be null");
        this.schemaKey = Objects.requireNonNull(schemaKey, "schemaKey cannot be null");
        this.data = data; // Can be null for DELETE operations
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
        this.changeType = Objects.requireNonNull(changeType, "changeType cannot be null");
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getSchemaKey() {
        return schemaKey;
    }

    public Object getData() {
        return data;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getKey() {
        return playerId + ":" + schemaKey;
    }

    public boolean isOlderThan(Instant threshold) {
        return timestamp.isBefore(threshold);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DirtyDataEntry that = (DirtyDataEntry) o;
        return Objects.equals(playerId, that.playerId) &&
                Objects.equals(schemaKey, that.schemaKey) &&
                Objects.equals(data, that.data) &&
                Objects.equals(timestamp, that.timestamp) &&
                changeType == that.changeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, schemaKey, data, timestamp, changeType);
    }

    @Override
    public String toString() {
        return "DirtyDataEntry{" +
                "playerId=" + playerId +
                ", schemaKey='" + schemaKey + '\'' +
                ", changeType=" + changeType +
                ", timestamp=" + timestamp +
                ", hasData=" + (data != null) +
                '}';
    }

    public enum ChangeType {
        /**
         * Data was created for the first time
         */
        CREATE,
        /**
         * Existing data was modified
         */
        UPDATE,
        /**
         * Data was deleted
         */
        DELETE
    }
}