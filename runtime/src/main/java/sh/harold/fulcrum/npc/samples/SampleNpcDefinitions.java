package sh.harold.fulcrum.npc.samples;

import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.behavior.NpcBehavior;
import sh.harold.fulcrum.npc.options.NpcEquipment;
import sh.harold.fulcrum.npc.options.NpcOptions;
import sh.harold.fulcrum.npc.pose.NpcPose;
import sh.harold.fulcrum.npc.profile.NpcProfile;
import sh.harold.fulcrum.npc.profile.NpcSkin;
import sh.harold.fulcrum.npc.visibility.NpcVisibility;

/**
 * Developer-facing sample definitions used in documentation and tests.
 */
public final class SampleNpcDefinitions {
    public static final String GREETER_ID = "sample:lobby_greeter";
    public static final NpcDefinition LOBBY_GREETER;

    static {
        NpcProfile profile = NpcProfile.builder()
                .displayName("&bRhea the Host")
                .description("Lobby Guide")
                .interactable(true)
                .skin(NpcSkin.fromTexturePayload("sample-texture", "sample-signature"))
                .build();

        NpcBehavior behavior = NpcBehavior.simple(builder -> builder
                .passiveIntervalTicks(40)
                .passive(ctx -> {
                })
                .interactionCooldownTicks(20));

        LOBBY_GREETER = NpcDefinition.builder()
                .id(GREETER_ID)
                .profile(profile)
                .pose(NpcPose.standing())
                .behavior(behavior)
                .visibility(NpcVisibility.everyone())
                .options(NpcOptions.builder().build())
                .equipment(NpcEquipment.empty())
                .poiAnchor("npc.greeter")
                .build();
    }

    private SampleNpcDefinitions() {
    }
}
