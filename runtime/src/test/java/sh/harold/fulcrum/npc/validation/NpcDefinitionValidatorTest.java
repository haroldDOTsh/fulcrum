package sh.harold.fulcrum.npc.validation;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.npc.NpcDefinition;
import sh.harold.fulcrum.npc.behavior.NpcBehavior;
import sh.harold.fulcrum.npc.options.NpcEquipment;
import sh.harold.fulcrum.npc.options.NpcOptions;
import sh.harold.fulcrum.npc.pose.NpcPose;
import sh.harold.fulcrum.npc.profile.NpcProfile;
import sh.harold.fulcrum.npc.profile.NpcSkin;
import sh.harold.fulcrum.npc.visibility.NpcVisibility;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcDefinitionValidatorTest {

    private final NpcDefinitionValidator validator = new NpcDefinitionValidator();

    private static NpcDefinition sampleDefinition(String id) {
        NpcProfile profile = NpcProfile.builder()
                .displayName("&bRhea the Host")
                .description("Lobby Guide")
                .skin(NpcSkin.fromTexturePayload("value", "signature"))
                .build();
        return NpcDefinition.builder()
                .id(id)
                .profile(profile)
                .pose(NpcPose.standing())
                .behavior(NpcBehavior.builder().build())
                .visibility(NpcVisibility.everyone())
                .options(NpcOptions.builder().build())
                .equipment(NpcEquipment.empty())
                .poiAnchor("test.anchor")
                .build();
    }

    @Test
    void duplicateIdsRejected() {
        NpcDefinition left = sampleDefinition("hub:lobby_guide");
        NpcDefinition right = sampleDefinition("hub:lobby_guide");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> validator.validateAllOrThrow(List.of(left, right)));

        assertTrue(exception.getMessage().contains("Duplicate NPC id"), exception::getMessage);
    }

    @Test
    void bannedColorCodesDetected() {
        NpcProfile profile = NpcProfile.builder()
                .displayName("&kMystery")
                .description("Secret Keeper")
                .skin(NpcSkin.fromTexturePayload("value", "signature"))
                .build();
        NpcDefinition definition = NpcDefinition.builder()
                .id("hub:mystery")
                .profile(profile)
                .pose(NpcPose.standing())
                .behavior(NpcBehavior.builder().build())
                .visibility(NpcVisibility.everyone())
                .options(NpcOptions.builder().build())
                .equipment(NpcEquipment.empty())
                .poiAnchor("npc.mystery")
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> validator.validateOrThrow(definition));

        assertTrue(exception.getMessage().contains("banned colour codes"), exception::getMessage);
    }
}
