package sh.harold.fulcrum.standard.guild;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.contracts.GuildContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuildCapabilityTest {
    @Test
    void guildDependsOnProfileContractAndMaterializesRosterProjections() {
        var profile = PlayerProfileCapability.descriptor();
        var guild = GuildCapability.descriptor();

        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(List.of(profile, guild));
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(List.of(profile, guild));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid(), () -> graphResult.errors().toString());
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID), graph.dependenciesFor(GuildCapability.CAPABILITY_ID));
        assertEquals(
                List.of(GuildContracts.ROSTER_PROJECTION, GuildContracts.SUBJECT_INDEX_PROJECTION),
                plan.projections().stream()
                        .filter(resource -> resource.capabilityId().equals(GuildCapability.CAPABILITY_ID))
                        .map(resource -> resource.declaration().relationName())
                        .toList());
    }

    @Test
    void guildContributesToProxyFanoutCommandsTabListAndMenuExtensionPoints() {
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                PlayerProfileCapability.descriptor(),
                GuildCapability.descriptor()));

        assertEquals(List.of(GuildCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_COMMANDS)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(GuildCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_PLAYER_FANOUT)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(GuildCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_TAB_LIST)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(GuildCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_MENUS)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }
}
