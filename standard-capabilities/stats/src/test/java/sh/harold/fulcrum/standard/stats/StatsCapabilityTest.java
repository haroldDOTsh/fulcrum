package sh.harold.fulcrum.standard.stats;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.StatsContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StatsCapabilityTest {
    @Test
    void descriptorDeclaresStatsContractAndProfileDependency() {
        var descriptors = List.of(PlayerProfileCapability.descriptor(), StatsCapability.descriptor());
        var validation = CapabilityDependencyGraphResolver.validate(descriptors);
        var graph = CapabilityDependencyGraphResolver.resolve(descriptors);

        assertTrue(validation.valid(), () -> validation.errors().toString());
        assertEquals(StatsCapability.CAPABILITY_ID, graph.providerOf(StatsContracts.CONTRACT).orElseThrow());
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(StatsCapability.CAPABILITY_ID));
    }

    @Test
    void descriptorContributesStatsSurfacesWithoutBecomingKernelConcept() {
        var graph = CapabilityDependencyGraphResolver.resolve(List.of(
                PlayerProfileCapability.descriptor(),
                StatsCapability.descriptor()));
        var plan = CapabilityMaterializationPlanner.plan(graph);
        var composer = CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK);

        assertEquals(List.of(StatsCapability.CAPABILITY_ID),
                composer.registrationsFor(CapabilityExtensionPoint.PAPER_SCOREBOARD).stream()
                        .map(registration -> registration.capabilityId())
                        .toList());
        assertTrue(plan.projections().stream()
                .map(resource -> resource.declaration().relationName())
                .toList()
                .containsAll(List.of(
                        StatsContracts.COUNTER_PROJECTION,
                        StatsContracts.EXPERIENCE_COUNTER_PROJECTION,
                        StatsContracts.LEDGER_PROJECTION)));
    }
}
