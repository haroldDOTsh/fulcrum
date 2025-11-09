package sh.harold.fulcrum.npc;

import org.bukkit.util.Vector;
import sh.harold.fulcrum.npc.behavior.NpcBehavior;
import sh.harold.fulcrum.npc.options.NpcEquipment;
import sh.harold.fulcrum.npc.options.NpcOptions;
import sh.harold.fulcrum.npc.pose.NpcPose;
import sh.harold.fulcrum.npc.profile.NpcProfile;
import sh.harold.fulcrum.npc.visibility.NpcVisibility;

import java.util.Objects;

/**
 * Immutable description of an NPC.
 */
public record NpcDefinition(
        String id,
        NpcProfile profile,
        NpcPose pose,
        NpcBehavior behavior,
        NpcVisibility visibility,
        NpcOptions options,
        NpcEquipment equipment,
        String poiAnchor,
        Vector relativeOffset,
        float yawOffset,
        float pitchOffset
) {

    public NpcDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("NPC definition id is required");
        }
        if (!id.contains(":")) {
            throw new IllegalArgumentException("NPC definition id must be namespaced (expected namespace:id)");
        }
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(behavior, "behavior");
        visibility = visibility != null ? visibility : NpcVisibility.everyone();
        options = options != null ? options : NpcOptions.builder().build();
        equipment = equipment != null ? equipment : NpcEquipment.empty();
        if (poiAnchor == null || poiAnchor.isBlank()) {
            throw new IllegalArgumentException("NPC definition " + id + " missing poiAnchor");
        }
        relativeOffset = relativeOffset != null ? relativeOffset.clone() : new Vector(0, 0, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private NpcProfile profile;
        private NpcPose pose = NpcPose.standing();
        private NpcBehavior behavior = NpcBehavior.builder().build();
        private NpcVisibility visibility = NpcVisibility.everyone();
        private NpcOptions options = NpcOptions.builder().build();
        private NpcEquipment equipment = NpcEquipment.empty();
        private String poiAnchor;
        private Vector relativeOffset = new Vector(0, 0, 0);
        private float yawOffset;
        private float pitchOffset;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder profile(NpcProfile profile) {
            this.profile = profile;
            return this;
        }

        public Builder pose(NpcPose pose) {
            this.pose = pose;
            return this;
        }

        public Builder behavior(NpcBehavior behavior) {
            this.behavior = behavior;
            return this;
        }

        public Builder visibility(NpcVisibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder options(NpcOptions options) {
            this.options = options;
            return this;
        }

        public Builder equipment(NpcEquipment equipment) {
            this.equipment = equipment;
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

        public NpcDefinition build() {
            return new NpcDefinition(id, profile, pose, behavior, visibility, options, equipment, poiAnchor,
                    relativeOffset, yawOffset, pitchOffset);
        }
    }
}
