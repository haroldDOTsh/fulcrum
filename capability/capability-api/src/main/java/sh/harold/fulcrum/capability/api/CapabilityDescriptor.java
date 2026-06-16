package sh.harold.fulcrum.capability.api;

import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.data.contract.ContractDeclaration;

import java.util.List;
import java.util.Objects;

public record CapabilityDescriptor(
        CapabilityId capabilityId,
        CapabilityVersion version,
        List<ContractName> requiredContracts,
        List<ContractDeclaration> declaredContracts,
        List<CapabilityAuthorityDeclaration> authorityDomains,
        List<ContributionDeclaration> contributions,
        List<CapabilityScope> allowedScopes) {
    public CapabilityDescriptor {
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        version = Objects.requireNonNull(version, "version");
        requiredContracts = List.copyOf(Objects.requireNonNull(requiredContracts, "requiredContracts"));
        declaredContracts = List.copyOf(Objects.requireNonNull(declaredContracts, "declaredContracts"));
        authorityDomains = List.copyOf(Objects.requireNonNull(authorityDomains, "authorityDomains"));
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        allowedScopes = List.copyOf(Objects.requireNonNull(allowedScopes, "allowedScopes"));
    }

    public boolean permitsScope(CapabilityScope requestedScope) {
        return allowedScopes.stream().anyMatch(scope -> scope.permits(requestedScope));
    }
}
