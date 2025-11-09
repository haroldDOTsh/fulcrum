package sh.harold.fulcrum.npc.poi;

import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * Associates an NPC definition with a POI anchor and relative offsets.
 */
public final class PoiNpcAssignment {
    private final String npcId;
    private final String poiAnchor;
    private final Vector relativeOffset;
    private final float yawOffset;
    private final float pitchOffset;

    private PoiNpcAssignment(Builder builder) {
        this.npcId = Objects.requireNonNull(builder.npcId, "npcId");
        this.poiAnchor = Objects.requireNonNull(builder.poiAnchor, "poiAnchor");
        this.relativeOffset = builder.relativeOffset.clone();
        this.yawOffset = builder.yawOffset;
        this.pitchOffset = builder.pitchOffset;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String npcId() {
        return npcId;
    }

    public String poiAnchor() {
        return poiAnchor;
    }

    public Vector relativeOffset() {
        return relativeOffset.clone();
    }

    public float yawOffset() {
        return yawOffset;
    }

    public float pitchOffset() {
        return pitchOffset;
    }

    public static final class Builder {
        private String npcId;
        private String poiAnchor;
        private Vector relativeOffset = new Vector(0, 0, 0);
        private float yawOffset;
        private float pitchOffset;

        public Builder npcId(String npcId) {
            this.npcId = npcId;
            return this;
        }

        public Builder poiAnchor(String poiAnchor) {
            this.poiAnchor = poiAnchor;
            return this;
        }

        public Builder relativeOffset(Vector relativeOffset) {
            this.relativeOffset = relativeOffset != null ? relativeOffset.clone() : new Vector(0, 0, 0);
            return this;
        }

        public Builder yawOffset(float yawOffset) {
            this.yawOffset = yawOffset;
            return this;
        }

        public Builder pitchOffset(float pitchOffset) {
            this.pitchOffset = pitchOffset;
            return this;
        }

        public PoiNpcAssignment build() {
            return new PoiNpcAssignment(this);
        }
    }
}
