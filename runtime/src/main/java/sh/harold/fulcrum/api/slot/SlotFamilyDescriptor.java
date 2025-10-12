package sh.harold.fulcrum.api.slot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Descriptor published by minigame modules to explain which logical slot families
 * they can host on a server (see docs/slot-family-discovery-notes.md).
 * <p>
 * Values are expressed in player-equivalent units so the registry can reason
 * about how many concurrent instances a node can support.
 */
public final class SlotFamilyDescriptor {
    private static final int DEFAULT_FACTOR = 10; // 10 == 1.0x load (see docs/slot-family-discovery-notes.md)

    private final String familyId;
    private final int minPlayers;
    private final int maxPlayers;
    private final int playerEquivalentFactor;
    private final Map<String, String> metadata;

    private SlotFamilyDescriptor(String familyId,
                                 int minPlayers,
                                 int maxPlayers,
                                 int playerEquivalentFactor,
                                 Map<String, String> metadata) {
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.playerEquivalentFactor = playerEquivalentFactor;
        this.metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    public static SlotFamilyDescriptor of(String familyId, int minPlayers, int maxPlayers) {
        return builder(familyId, minPlayers, maxPlayers).build();
    }

    public static Builder builder(String familyId, int minPlayers, int maxPlayers) {
        return new Builder(familyId, minPlayers, maxPlayers);
    }

    public String getFamilyId() {
        return familyId;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    /**
     * Player-equivalent multiplier expressed as an integer scaled by 10.
     * A factor of 10 means the game consumes 1.0x player budget (default),
     * 11 means 1.1x, etc. (docs/slot-family-discovery-notes.md).
     */
    public int getPlayerEquivalentFactor() {
        return playerEquivalentFactor;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public Builder toBuilder() {
        return new Builder(familyId, minPlayers, maxPlayers)
                .playerEquivalentFactor(playerEquivalentFactor)
                .addAllMetadata(metadata);
    }

    public static final class Builder {
        private final String familyId;
        private final int minPlayers;
        private final int maxPlayers;
        private final Map<String, String> metadata = new HashMap<>();
        private int playerEquivalentFactor = DEFAULT_FACTOR;

        private Builder(String familyId, int minPlayers, int maxPlayers) {
            this.familyId = Objects.requireNonNull(familyId, "familyId");
            this.minPlayers = minPlayers;
            this.maxPlayers = maxPlayers;
        }

        public Builder playerEquivalentFactor(int playerEquivalentFactor) {
            this.playerEquivalentFactor = playerEquivalentFactor;
            return this;
        }

        public Builder putMetadata(String key, String value) {
            metadata.put(key, value);
            return this;
        }

        public Builder addAllMetadata(Map<String, String> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public SlotFamilyDescriptor build() {
            if (minPlayers < 0) {
                throw new IllegalArgumentException("minPlayers must be >= 0 for family " + familyId);
            }
            if (maxPlayers < minPlayers) {
                throw new IllegalArgumentException("maxPlayers must be >= minPlayers for family " + familyId);
            }
            if (playerEquivalentFactor < DEFAULT_FACTOR) {
                throw new IllegalArgumentException("playerEquivalentFactor must be >= " + DEFAULT_FACTOR);
            }
            return new SlotFamilyDescriptor(
                    familyId,
                    minPlayers,
                    maxPlayers,
                    playerEquivalentFactor,
                    metadata
            );
        }
    }
}
