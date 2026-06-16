package sh.harold.fulcrum.standard.friends;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.FriendsContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FriendsCapabilityTest {
    @Test
    void friendsDependsOnProfileContractAndMaterializesBidirectionalProjections() {
        var profile = PlayerProfileCapability.descriptor();
        var friends = FriendsCapability.descriptor();

        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(List.of(profile, friends));
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(List.of(profile, friends));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid(), () -> graphResult.errors().toString());
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID), graph.dependenciesFor(FriendsCapability.CAPABILITY_ID));
        assertEquals(
                List.of(FriendsContracts.CONNECTION_PROJECTION, FriendsContracts.SUBJECT_INDEX_PROJECTION),
                plan.projections().stream()
                        .filter(resource -> resource.capabilityId().equals(FriendsCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().relationName())
                        .toList());
    }

    @Test
    void friendsContributesToProxyFanoutCommandsAndTabListExtensionPoints() {
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                PlayerProfileCapability.descriptor(),
                FriendsCapability.descriptor()));

        assertEquals(List.of(FriendsCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_COMMANDS)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(FriendsCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_PLAYER_FANOUT)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(FriendsCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_TAB_LIST)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }
}
