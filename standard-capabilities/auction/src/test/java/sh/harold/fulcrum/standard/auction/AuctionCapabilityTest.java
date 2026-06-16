package sh.harold.fulcrum.standard.auction;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.AuctionContracts;
import sh.harold.fulcrum.standard.economy.EconomyCapability;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionCapabilityTest {
    @Test
    void descriptorDeclaresAuctionContractAndEconomyDependency() {
        var descriptors = List.of(
                PlayerProfileCapability.descriptor(),
                EconomyCapability.descriptor(),
                AuctionCapability.descriptor());
        var validation = CapabilityDependencyGraphResolver.validate(descriptors);
        var graph = CapabilityDependencyGraphResolver.resolve(descriptors);

        assertTrue(validation.valid(), () -> validation.errors().toString());
        assertEquals(AuctionCapability.CAPABILITY_ID, graph.providerOf(AuctionContracts.CONTRACT).orElseThrow());
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID, EconomyCapability.CAPABILITY_ID),
                graph.dependenciesFor(AuctionCapability.CAPABILITY_ID));
    }

    @Test
    void descriptorContributesAuctionSurfacesWithoutBecomingKernelConcept() {
        var graph = CapabilityDependencyGraphResolver.resolve(List.of(
                PlayerProfileCapability.descriptor(),
                EconomyCapability.descriptor(),
                AuctionCapability.descriptor()));
        var plan = CapabilityMaterializationPlanner.plan(graph);
        var composer = CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK);

        assertEquals(List.of(AuctionCapability.CAPABILITY_ID),
                composer.registrationsFor(CapabilityExtensionPoint.EXPERIENCE_UI_SURFACE).stream()
                        .map(registration -> registration.capabilityId())
                        .toList());
        assertTrue(plan.projections().stream()
                .map(resource -> resource.declaration().relationName())
                .toList()
                .containsAll(List.of(
                        AuctionContracts.LISTING_PROJECTION,
                        AuctionContracts.ESCROW_PROJECTION,
                        AuctionContracts.AUDIT_PROJECTION)));
    }
}
