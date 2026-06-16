package sh.harold.fulcrum.capability.runtime;

import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityValidationError;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.data.contract.AclRuleDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CapabilityMaterializationPlanner {
    private CapabilityMaterializationPlanner() {
    }

    public static CapabilityValidationResult validate(List<CapabilityDescriptor> descriptors) {
        List<CapabilityDescriptor> checkedDescriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        List<CapabilityValidationError> errors = new ArrayList<>(
                CapabilityDependencyGraphResolver.validate(checkedDescriptors).errors());
        addDuplicateAuthorityErrors(errors, checkedDescriptors);
        addDuplicateContractResourceErrors(errors, checkedDescriptors);
        return new CapabilityValidationResult(errors);
    }

    public static CapabilityMaterializationPlan plan(List<CapabilityDescriptor> descriptors) {
        List<CapabilityDescriptor> checkedDescriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        CapabilityValidationResult validationResult = validate(checkedDescriptors);
        if (!validationResult.valid()) {
            throw new IllegalArgumentException("invalid capability materialization plan: " + validationResult.errors());
        }
        return plan(CapabilityDependencyGraphResolver.resolve(checkedDescriptors));
    }

    public static CapabilityMaterializationPlan plan(CapabilityDependencyGraph dependencyGraph) {
        CapabilityDependencyGraph graph = Objects.requireNonNull(dependencyGraph, "dependencyGraph");
        List<CapabilityMaterializationPlan.DeclaredContract> contracts = new ArrayList<>();
        List<CapabilityMaterializationPlan.AuthorityResource> authorities = new ArrayList<>();
        List<CapabilityMaterializationPlan.TopicResource> topics = new ArrayList<>();
        List<CapabilityMaterializationPlan.ProjectionResource> projections = new ArrayList<>();
        List<CapabilityMaterializationPlan.AclResource> aclRules = new ArrayList<>();
        List<CapabilityMaterializationPlan.ContributionRegistration> contributions = new ArrayList<>();

        for (CapabilityDescriptor descriptor : graph.capabilities()) {
            for (ContractDeclaration contract : descriptor.declaredContracts()) {
                contracts.add(new CapabilityMaterializationPlan.DeclaredContract(
                        descriptor.capabilityId(),
                        contract.name(),
                        contract));
                for (TopicDeclaration topic : contract.topics()) {
                    topics.add(new CapabilityMaterializationPlan.TopicResource(
                            descriptor.capabilityId(),
                            contract.name(),
                            topic));
                }
                for (ProjectionDeclaration projection : contract.projections()) {
                    projections.add(new CapabilityMaterializationPlan.ProjectionResource(
                            descriptor.capabilityId(),
                            contract.name(),
                            projection));
                }
                for (AclRuleDeclaration aclRule : contract.aclRules()) {
                    aclRules.add(new CapabilityMaterializationPlan.AclResource(
                            descriptor.capabilityId(),
                            contract.name(),
                            aclRule));
                }
            }
            for (CapabilityAuthorityDeclaration authority : descriptor.authorityDomains()) {
                authorities.add(new CapabilityMaterializationPlan.AuthorityResource(
                        descriptor.capabilityId(),
                        authority));
            }
            descriptor.contributions().stream()
                    .map(contribution -> new CapabilityMaterializationPlan.ContributionRegistration(
                            descriptor.capabilityId(),
                            contribution))
                    .forEach(contributions::add);
        }

        return new CapabilityMaterializationPlan(
                graph,
                contracts,
                authorities,
                topics,
                projections,
                aclRules,
                contributions);
    }

    private static void addDuplicateAuthorityErrors(
            List<CapabilityValidationError> errors,
            List<CapabilityDescriptor> descriptors) {
        Set<String> authorityDomains = new HashSet<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            for (CapabilityAuthorityDeclaration authority : descriptor.authorityDomains()) {
                if (!authorityDomains.add(authority.authorityDomain())) {
                    errors.add(new CapabilityValidationError(
                            "materialization.authority.duplicate",
                            authority.authorityDomain()));
                }
            }
        }
    }

    private static void addDuplicateContractResourceErrors(
            List<CapabilityValidationError> errors,
            List<CapabilityDescriptor> descriptors) {
        Set<String> topicNames = new HashSet<>();
        Set<String> projectionRelations = new HashSet<>();
        Set<String> aclResources = new HashSet<>();
        for (CapabilityDescriptor descriptor : descriptors) {
            for (ContractDeclaration contract : descriptor.declaredContracts()) {
                for (TopicDeclaration topic : contract.topics()) {
                    if (!topicNames.add(topic.name())) {
                        errors.add(new CapabilityValidationError("materialization.topic.duplicate", topic.name()));
                    }
                }
                for (ProjectionDeclaration projection : contract.projections()) {
                    if (!projectionRelations.add(projection.relationName())) {
                        errors.add(new CapabilityValidationError(
                                "materialization.projection.duplicate",
                                projection.relationName()));
                    }
                }
                for (AclRuleDeclaration aclRule : contract.aclRules()) {
                    if (!aclResources.add(aclRule.resource())) {
                        errors.add(new CapabilityValidationError("materialization.acl.duplicate", aclRule.resource()));
                    }
                }
            }
        }
    }
}
