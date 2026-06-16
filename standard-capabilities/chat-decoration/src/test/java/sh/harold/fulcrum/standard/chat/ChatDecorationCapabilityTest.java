package sh.harold.fulcrum.standard.chat;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;
import sh.harold.fulcrum.standard.rank.RankCapability;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatDecorationCapabilityTest {
    @Test
    void chatDecorationRequiresRankContractAndMaterializesNoStateResources() {
        var profile = PlayerProfileCapability.descriptor();
        var rank = RankCapability.descriptor();
        var chatDecoration = ChatDecorationCapability.descriptor();

        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(List.of(
                profile,
                rank,
                chatDecoration));
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(List.of(
                profile,
                rank,
                chatDecoration));
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid(), () -> graphResult.errors().toString());
        assertEquals(List.of(RankCapability.CAPABILITY_ID),
                graph.dependenciesFor(ChatDecorationCapability.CAPABILITY_ID));
        assertTrue(plan.contracts().stream()
                .noneMatch(resource -> resource.capabilityId().equals(ChatDecorationCapability.CAPABILITY_ID)));
        assertTrue(plan.authorities().stream()
                .noneMatch(resource -> resource.capabilityId().equals(ChatDecorationCapability.CAPABILITY_ID)));
        assertTrue(plan.projections().stream()
                .noneMatch(resource -> resource.capabilityId().equals(ChatDecorationCapability.CAPABILITY_ID)));
    }

    @Test
    void chatDecorationContributesAfterRankInPaperChatPipeline() {
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(
                PlayerProfileCapability.descriptor(),
                RankCapability.descriptor(),
                ChatDecorationCapability.descriptor()));

        assertEquals(List.of(RankCapability.CAPABILITY_ID, ChatDecorationCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }
}
