package sh.harold.fulcrum.standard.rank;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RankCapabilityTest {
    @Test
    void rankDependsOnProfileContractAndMaterializesOneLiveReadPath() {
        var profile = PlayerProfileCapability.descriptor();
        var rank = RankCapability.descriptor();

        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(List.of(profile, rank));
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(List.of(profile, rank));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid());
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID), graph.dependenciesFor(RankCapability.CAPABILITY_ID));
        assertEquals(List.of(RankContracts.EFFECTIVE_PROJECTION),
                plan.projections().stream()
                        .filter(resource -> resource.capabilityId().equals(RankCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().relationName())
                        .toList());
    }

    @Test
    void rankContributesToClosedHostExtensionPoints() {
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                PlayerProfileCapability.descriptor(),
                RankCapability.descriptor()));

        assertEquals(List.of(new CapabilityId("rank")),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(new CapabilityId("rank")),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_COMMANDS)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }
}
