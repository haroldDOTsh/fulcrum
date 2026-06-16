package sh.harold.fulcrum.capability.api;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.data.contract.ContractDeclaration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapabilityManifestValidatorTest {
    @Test
    void validManifestAllowsScopedContributionAndNarrowedEnablement() {
        CapabilityDescriptor descriptor = descriptor(
                List.of(new ContractName("sample.required.v1")),
                List.of(contract("sample.provided.v1")),
                List.of(new CapabilityAuthorityDeclaration("sample-by-subject", "standard", 8)),
                List.of(new ContributionDeclaration(
                        CapabilityExtensionPoint.PAPER_CHAT_PIPELINE,
                        CapabilityScope.pool(new PoolId("pool-paper-main")),
                        10)),
                List.of(CapabilityScope.NETWORK));

        CapabilityValidationResult manifestResult = CapabilityManifestValidator.validate(descriptor);
        CapabilityValidationResult enablementResult = CapabilityManifestValidator.validateEnablement(
                descriptor,
                new CapabilityEnablement(
                        descriptor.capabilityId(),
                        descriptor.version(),
                        CapabilityScope.pool(new PoolId("pool-paper-main"))));

        assertTrue(manifestResult.valid());
        assertTrue(enablementResult.valid());
    }

    @Test
    void enablementCannotWidenDeclaredScope() {
        CapabilityDescriptor descriptor = descriptor(
                List.of(),
                List.of(contract("sample.provided.v1")),
                List.of(),
                List.of(),
                List.of(CapabilityScope.pool(new PoolId("pool-paper-main"))));

        CapabilityValidationResult result = CapabilityManifestValidator.validateEnablement(
                descriptor,
                new CapabilityEnablement(descriptor.capabilityId(), descriptor.version(), CapabilityScope.NETWORK));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "enablement.scope.widening"));
    }

    @Test
    void contributionScopeMustStayInsideManifestScope() {
        CapabilityDescriptor descriptor = descriptor(
                List.of(),
                List.of(contract("sample.provided.v1")),
                List.of(),
                List.of(new ContributionDeclaration(
                        CapabilityExtensionPoint.PROXY_LOGIN_GATE,
                        CapabilityScope.pool(new PoolId("pool-paper-main")),
                        0)),
                List.of(CapabilityScope.pool(new PoolId("pool-paper-other"))));

        CapabilityValidationResult result = CapabilityManifestValidator.validate(descriptor);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "contribution.scope.not-allowed"));
    }

    @Test
    void duplicateContributionOrderSlotIsRejected() {
        CapabilityDescriptor descriptor = descriptor(
                List.of(),
                List.of(contract("sample.provided.v1")),
                List.of(),
                List.of(
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 10),
                        new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 10)),
                List.of(CapabilityScope.NETWORK));

        CapabilityValidationResult result = CapabilityManifestValidator.validate(descriptor);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "contribution.order.duplicate"));
    }

    @Test
    void duplicateContractsAuthoritiesAndScopesAreRejected() {
        ContractName required = new ContractName("sample.required.v1");
        CapabilityDescriptor descriptor = descriptor(
                List.of(required, required),
                List.of(contract("sample.provided.v1"), contract("sample.provided.v1")),
                List.of(
                        new CapabilityAuthorityDeclaration("sample-by-subject", "standard", 8),
                        new CapabilityAuthorityDeclaration("sample-by-subject", "standard", 8)),
                List.of(),
                List.of(CapabilityScope.NETWORK, CapabilityScope.NETWORK));

        CapabilityValidationResult result = CapabilityManifestValidator.validate(descriptor);

        assertFalse(result.valid());
        assertTrue(hasCode(result, "contract.required.duplicate"));
        assertTrue(hasCode(result, "contract.declared.duplicate"));
        assertTrue(hasCode(result, "authority.duplicate"));
        assertTrue(hasCode(result, "scope.duplicate"));
    }

    private static CapabilityDescriptor descriptor(
            List<ContractName> requiredContracts,
            List<ContractDeclaration> declaredContracts,
            List<CapabilityAuthorityDeclaration> authorityDomains,
            List<ContributionDeclaration> contributions,
            List<CapabilityScope> allowedScopes) {
        return new CapabilityDescriptor(
                new CapabilityId("sample-capability"),
                new CapabilityVersion("1.0.0"),
                requiredContracts,
                declaredContracts,
                authorityDomains,
                contributions,
                allowedScopes);
    }

    private static ContractDeclaration contract(String name) {
        return new ContractDeclaration(new ContractName(name), List.of(), List.of(), List.of());
    }

    private static boolean hasCode(CapabilityValidationResult result, String code) {
        return result.errors().stream().anyMatch(error -> error.code().equals(code));
    }
}
