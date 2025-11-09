package sh.harold.fulcrum.npc;

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
        NpcEquipment equipment
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
    }
}
