package sh.harold.fulcrum.capability.runtime;

import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.data.contract.AclRuleDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;

import java.util.List;
import java.util.Objects;

public record CapabilityMaterializationPlan(
        CapabilityDependencyGraph dependencyGraph,
        List<DeclaredContract> contracts,
        List<AuthorityResource> authorities,
        List<TopicResource> topics,
        List<ProjectionResource> projections,
        List<AclResource> aclRules,
        List<ContributionRegistration> contributions) {
    public CapabilityMaterializationPlan {
        dependencyGraph = Objects.requireNonNull(dependencyGraph, "dependencyGraph");
        contracts = List.copyOf(Objects.requireNonNull(contracts, "contracts"));
        authorities = List.copyOf(Objects.requireNonNull(authorities, "authorities"));
        topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
        projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
        aclRules = List.copyOf(Objects.requireNonNull(aclRules, "aclRules"));
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
    }

    public record DeclaredContract(
            CapabilityId capabilityId,
            ContractName contractName,
            ContractDeclaration declaration) {
        public DeclaredContract {
            capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
            contractName = Objects.requireNonNull(contractName, "contractName");
            declaration = Objects.requireNonNull(declaration, "declaration");
        }
    }

    public record AuthorityResource(
            CapabilityId capabilityId,
            CapabilityAuthorityDeclaration declaration) {
        public AuthorityResource {
            capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
            declaration = Objects.requireNonNull(declaration, "declaration");
        }
    }

    public record TopicResource(
            CapabilityId capabilityId,
            ContractName contractName,
            TopicDeclaration declaration) {
        public TopicResource {
            capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
            contractName = Objects.requireNonNull(contractName, "contractName");
            declaration = Objects.requireNonNull(declaration, "declaration");
        }
    }

    public record ProjectionResource(
            CapabilityId capabilityId,
            ContractName contractName,
            ProjectionDeclaration declaration) {
        public ProjectionResource {
            capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
            contractName = Objects.requireNonNull(contractName, "contractName");
            declaration = Objects.requireNonNull(declaration, "declaration");
        }
    }

    public record AclResource(
            CapabilityId capabilityId,
            ContractName contractName,
            AclRuleDeclaration declaration) {
        public AclResource {
            capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
            contractName = Objects.requireNonNull(contractName, "contractName");
            declaration = Objects.requireNonNull(declaration, "declaration");
        }
    }

    public record ContributionRegistration(
            CapabilityId capabilityId,
            ContributionDeclaration declaration) {
        public ContributionRegistration {
            capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
            declaration = Objects.requireNonNull(declaration, "declaration");
        }
    }
}
