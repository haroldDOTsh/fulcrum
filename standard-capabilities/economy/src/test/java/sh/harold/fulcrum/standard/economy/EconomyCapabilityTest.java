package sh.harold.fulcrum.standard.economy;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EconomyCapabilityTest {
    @Test
    void descriptorDeclaresEconomyContractAndProfileDependency() {
        var descriptors = List.of(PlayerProfileCapability.descriptor(), EconomyCapability.descriptor());
        var validation = CapabilityDependencyGraphResolver.validate(descriptors);
        var graph = CapabilityDependencyGraphResolver.resolve(descriptors);

        assertTrue(validation.valid(), () -> validation.errors().toString());
        assertEquals(EconomyCapability.CAPABILITY_ID, graph.providerOf(EconomyContracts.CONTRACT).orElseThrow());
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(EconomyCapability.CAPABILITY_ID));
    }

    @Test
    void descriptorContributesEconomySurfacesWithoutBecomingKernelConcept() {
        var graph = CapabilityDependencyGraphResolver.resolve(List.of(
                PlayerProfileCapability.descriptor(),
                EconomyCapability.descriptor()));
        var plan = CapabilityMaterializationPlanner.plan(graph);
        var composer = CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK);

        assertEquals(List.of(EconomyCapability.CAPABILITY_ID),
                composer.registrationsFor(CapabilityExtensionPoint.PAPER_SCOREBOARD).stream()
                        .map(registration -> registration.capabilityId())
                        .toList());
        assertTrue(plan.projections().stream()
                .map(resource -> resource.declaration().relationName())
                .toList()
                .containsAll(List.of(EconomyContracts.BALANCE_PROJECTION, EconomyContracts.LEDGER_PROJECTION)));
    }
}
