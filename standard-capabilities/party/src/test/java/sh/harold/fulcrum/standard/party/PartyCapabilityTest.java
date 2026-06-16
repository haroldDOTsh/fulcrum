package sh.harold.fulcrum.standard.party;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.PartyContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PartyCapabilityTest {
    @Test
    void partyDependsOnProfileContractAndMaterializesRosterProjections() {
        var profile = PlayerProfileCapability.descriptor();
        var party = PartyCapability.descriptor();

        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(List.of(profile, party));
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(List.of(profile, party));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid(), () -> graphResult.errors().toString());
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID), graph.dependenciesFor(PartyCapability.CAPABILITY_ID));
        assertEquals(
                List.of(PartyContracts.ROSTER_PROJECTION, PartyContracts.SUBJECT_INDEX_PROJECTION),
                plan.projections().stream()
                        .filter(resource -> resource.capabilityId().equals(PartyCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().relationName())
                        .toList());
    }

    @Test
    void partyContributesToQueueRosterAndProxyCommandExtensionPoints() {
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                PlayerProfileCapability.descriptor(),
                PartyCapability.descriptor()));

        assertEquals(List.of(PartyCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.EXPERIENCE_QUEUE_POLICY)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(PartyCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.EXPERIENCE_ROSTER_POLICY)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(PartyCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_COMMANDS)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }
}
