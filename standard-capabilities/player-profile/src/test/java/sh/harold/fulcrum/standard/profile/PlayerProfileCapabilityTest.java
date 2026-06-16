package sh.harold.fulcrum.standard.profile;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityManifestValidator;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerProfileCapabilityTest {
    @Test
    void descriptorValidatesAndMaterializesProfileResources() {
        var descriptor = PlayerProfileCapability.descriptor();

        assertTrue(CapabilityManifestValidator.validate(descriptor).valid());

        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(descriptor));
        assertEquals(List.of(PlayerProfileContracts.CONTRACT),
                plan.contracts().stream().map(CapabilityMaterializationPlan.DeclaredContract::contractName).toList());
        assertEquals(List.of("profile-by-subject"),
                plan.authorities().stream().map(resource -> resource.declaration().authorityDomain()).toList());
        assertEquals(List.of(PlayerProfileContracts.EFFECTIVE_PROJECTION),
                plan.projections().stream().map(resource -> resource.declaration().relationName()).toList());
    }
}
