package sh.harold.fulcrum.capability.api;

import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.data.contract.ContractDeclaration;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CapabilityManifestValidator {
    private CapabilityManifestValidator() {
    }

    public static CapabilityValidationResult validate(CapabilityDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        List<CapabilityValidationError> errors = new java.util.ArrayList<>();
        if (descriptor.allowedScopes().isEmpty()) {
            errors.add(new CapabilityValidationError("scope.required", "capability must declare at least one allowed scope"));
        }
        addDuplicateErrors(errors, "contract.required.duplicate", descriptor.requiredContracts().stream().map(ContractName::value).toList());
        addDuplicateErrors(errors, "contract.declared.duplicate", descriptor.declaredContracts().stream().map(ContractDeclaration::name).map(ContractName::value).toList());
        addDuplicateErrors(errors, "authority.duplicate", descriptor.authorityDomains().stream().map(CapabilityAuthorityDeclaration::authorityDomain).toList());
        addDuplicateErrors(errors, "scope.duplicate", descriptor.allowedScopes().stream().map(CapabilityScope::value).toList());

        for (ContributionDeclaration contribution : descriptor.contributions()) {
            if (!descriptor.permitsScope(contribution.scope())) {
                errors.add(new CapabilityValidationError(
                        "contribution.scope.not-allowed",
                        contribution.extensionPoint().wireName() + " uses disallowed scope " + contribution.scope().value()));
            }
        }
        Set<String> contributionSlots = new HashSet<>();
        for (ContributionDeclaration contribution : descriptor.contributions()) {
            String slot = contribution.extensionPoint().wireName() + "|" + contribution.scope().value() + "|" + contribution.order();
            if (!contributionSlots.add(slot)) {
                errors.add(new CapabilityValidationError("contribution.order.duplicate", slot));
            }
        }
        return new CapabilityValidationResult(errors);
    }

    public static CapabilityValidationResult validateEnablement(
            CapabilityDescriptor descriptor,
            CapabilityEnablement enablement) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(enablement, "enablement");
        List<CapabilityValidationError> errors = new java.util.ArrayList<>(validate(descriptor).errors());
        if (!descriptor.capabilityId().equals(enablement.capabilityId())) {
            errors.add(new CapabilityValidationError("enablement.capability.mismatch", enablement.capabilityId().value()));
        }
        if (!descriptor.version().equals(enablement.version())) {
            errors.add(new CapabilityValidationError("enablement.version.mismatch", enablement.version().value()));
        }
        if (!descriptor.permitsScope(enablement.scope())) {
            errors.add(new CapabilityValidationError("enablement.scope.widening", enablement.scope().value()));
        }
        return new CapabilityValidationResult(errors);
    }

    private static void addDuplicateErrors(List<CapabilityValidationError> errors, String code, List<String> values) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                errors.add(new CapabilityValidationError(code, value));
            }
        }
    }
}
