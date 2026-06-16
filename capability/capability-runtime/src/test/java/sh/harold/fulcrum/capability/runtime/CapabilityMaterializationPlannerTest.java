package sh.harold.fulcrum.capability.runtime;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.data.contract.AclRuleDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.FieldDeclaration;
import sh.harold.fulcrum.data.contract.FieldType;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;
import sh.harold.fulcrum.data.contract.TopicFamily;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapabilityMaterializationPlannerTest {
    @Test
    void materializesDeclaredCapabilityResourcesInDescriptorOrder() {
        CapabilityDescriptor profile = descriptor(
                "player-profile",
                List.of(),
                List.of(contract(
                        "profile.v1",
                        "cmd.profile",
                        "evt.profile",
                        "profile_hot_projection",
                        "profile_projection",
                        "profile-authority")),
                List.of(new CapabilityAuthorityDeclaration("profile-by-subject", "standard", 16)),
                List.of(new ContributionDeclaration(CapabilityExtensionPoint.PAPER_TAB_LIST, CapabilityScope.NETWORK, 20)));
        CapabilityDescriptor rank = descriptor(
                "rank",
                List.of(new ContractName("profile.v1")),
                List.of(contract(
                        "rank.v1",
                        "cmd.rank",
                        "evt.rank",
                        "rank_hot_projection",
                        "rank_projection",
                        "rank-authority")),
                List.of(new CapabilityAuthorityDeclaration("rank-by-subject", "standard", 16)),
                List.of(new ContributionDeclaration(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE, CapabilityScope.NETWORK, 10)));

        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(profile, rank));

        assertEquals(List.of(new CapabilityId("player-profile")), plan.dependencyGraph().dependenciesFor(new CapabilityId("rank")));
        assertEquals(List.of(new ContractName("profile.v1"), new ContractName("rank.v1")),
                plan.contracts().stream().map(CapabilityMaterializationPlan.DeclaredContract::contractName).toList());
        assertEquals(List.of("profile-by-subject", "rank-by-subject"),
                plan.authorities().stream().map(resource -> resource.declaration().authorityDomain()).toList());
        assertEquals(List.of("cmd.profile", "evt.profile", "cmd.rank", "evt.rank"),
                plan.topics().stream().map(resource -> resource.declaration().name()).toList());
        assertEquals(List.of("profile_hot_projection", "rank_hot_projection"),
                plan.projections().stream().map(resource -> resource.declaration().relationName()).toList());
        assertEquals(List.of("cmd.profile", "evt.profile", "cmd.rank", "evt.rank"),
                plan.aclRules().stream().map(resource -> resource.declaration().resource()).toList());
        assertEquals(List.of(CapabilityExtensionPoint.PAPER_TAB_LIST, CapabilityExtensionPoint.PAPER_CHAT_PIPELINE),
                plan.contributions().stream().map(resource -> resource.declaration().extensionPoint()).toList());
    }

    @Test
    void materializationRejectsMissingDependencyBeforePlanning() {
        CapabilityDescriptor rank = descriptor(
                "rank",
                List.of(new ContractName("profile.v1")),
                List.of(contract("rank.v1", "cmd.rank", "evt.rank", "rank_hot_projection", "rank_projection", "rank-authority")),
                List.of(),
                List.of());

        CapabilityValidationResult result = CapabilityMaterializationPlanner.validate(List.of(rank));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "graph.contract.missing"));
        assertThrows(IllegalArgumentException.class, () -> CapabilityMaterializationPlanner.plan(List.of(rank)));
    }

    @Test
    void duplicateMaterializedResourcesAreRejected() {
        CapabilityDescriptor profile = descriptor(
                "player-profile",
                List.of(),
                List.of(contract(
                        "profile.v1",
                        "cmd.shared",
                        "evt.profile",
                        "shared_relation",
                        "profile_projection",
                        "shared-principal")),
                List.of(new CapabilityAuthorityDeclaration("shared-authority", "standard", 4)),
                List.of());
        CapabilityDescriptor rank = descriptor(
                "rank",
                List.of(),
                List.of(contract(
                        "rank.v1",
                        "cmd.shared",
                        "evt.rank",
                        "shared_relation",
                        "rank_projection",
                        "shared-principal")),
                List.of(new CapabilityAuthorityDeclaration("shared-authority", "standard", 4)),
                List.of());

        CapabilityValidationResult result = CapabilityMaterializationPlanner.validate(List.of(profile, rank));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "materialization.authority.duplicate"));
        assertTrue(hasCode(result, "materialization.topic.duplicate"));
        assertTrue(hasCode(result, "materialization.projection.duplicate"));
        assertTrue(hasCode(result, "materialization.acl.duplicate"));
    }

    private static CapabilityDescriptor descriptor(
            String capabilityId,
            List<ContractName> requiredContracts,
            List<ContractDeclaration> declaredContracts,
            List<CapabilityAuthorityDeclaration> authorityDomains,
            List<ContributionDeclaration> contributions) {
        return new CapabilityDescriptor(
                new CapabilityId(capabilityId),
                new CapabilityVersion("1.0.0"),
                requiredContracts,
                declaredContracts,
                authorityDomains,
                contributions,
                List.of(CapabilityScope.NETWORK));
    }

    private static ContractDeclaration contract(
            String name,
            String commandTopic,
            String eventTopic,
            String projectionRelation,
            String projectionName,
            String principal) {
        return new ContractDeclaration(
                new ContractName(name),
                List.of(),
                List.of(),
                java.util.Optional.empty(),
                List.of(new ProjectionDeclaration(
                        projectionName,
                        projectionRelation,
                        List.of(new FieldDeclaration("subject_id", FieldType.STRING, false)))),
                List.of(
                        new TopicDeclaration(commandTopic, TopicFamily.COMMAND),
                        new TopicDeclaration(eventTopic, TopicFamily.EVENT)),
                List.of(
                        new AclRuleDeclaration(commandTopic, List.of(principal + "-client"), List.of(principal)),
                        new AclRuleDeclaration(eventTopic, List.of(principal), List.of(principal + "-projection"))));
    }

    private static boolean hasCode(CapabilityValidationResult result, String code) {
        return result.errors().stream().anyMatch(error -> error.code().equals(code));
    }
}
