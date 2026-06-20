package sh.harold.fulcrum.sdk.authoring;

import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityValidationError;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class AuthorBundlePreflight {
    private static final String FULCRUM_PACKAGE = "sh.harold.fulcrum.";
    private static final List<String> FORBIDDEN_PROVIDER_PREFIXES = List.of(
            FULCRUM_PACKAGE + "control.",
            FULCRUM_PACKAGE + "data." + "store.",
            FULCRUM_PACKAGE + "distribution.");

    private AuthorBundlePreflight() {
    }

    public static AuthorBundlePreflightReceipt evaluate(AuthorBundlePreflightRequest request) {
        Objects.requireNonNull(request, "request");
        List<AuthorBundlePreflightRefusal> refusals = new ArrayList<>();
        String actualDescriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(request.descriptor());
        if (!actualDescriptorDigest.equals(request.descriptorDigest())) {
            refusals.add(refusal(
                    AuthorBundlePreflightRefusalCode.DESCRIPTOR_DIGEST_MISMATCH,
                    "declared descriptor digest does not match descriptor"));
        }
        if (!request.artifactDigest().matches("[0-9a-f]{64}")) {
            refusals.add(refusal(
                    AuthorBundlePreflightRefusalCode.ARTIFACT_DIGEST_MISMATCH,
                    "artifact digest must be a lower-case sha-256 hex digest"));
        }
        if (request.providerClassNames().isEmpty()) {
            refusals.add(refusal(
                    AuthorBundlePreflightRefusalCode.PROVIDER_UNDECLARED,
                    "bundle must declare at least one provider"));
        }
        request.providerClassNames().stream()
                .filter(provider -> FORBIDDEN_PROVIDER_PREFIXES.stream().anyMatch(provider::startsWith))
                .map(provider -> refusal(
                        AuthorBundlePreflightRefusalCode.PROVIDER_SHADOWED_SUBSTRATE_CLASS,
                        provider))
                .forEach(refusals::add);
        request.contributions().stream()
                .filter(contribution -> !request.descriptor().contributions().contains(contribution))
                .map(contribution -> refusal(
                        AuthorBundlePreflightRefusalCode.PROVIDER_UNDECLARED,
                        contribution.extensionPoint().wireName() + ":" + contribution.scope().value() + ":" + contribution.order()))
                .forEach(refusals::add);
        request.descriptor().contributions().stream()
                .filter(contribution -> !request.descriptor().permitsScope(contribution.scope()))
                .map(contribution -> refusal(
                        AuthorBundlePreflightRefusalCode.SCOPE_UNSUPPORTED,
                        contribution.scope().value()))
                .forEach(refusals::add);
        addGrantRefusals(request, refusals);
        CapabilityMaterializationPlanner.validate(List.of(request.descriptor())).errors().stream()
                .map(AuthorBundlePreflight::mapValidationError)
                .forEach(refusals::add);

        List<AuthorBundlePreflightRefusal> sortedRefusals = refusals.stream()
                .distinct()
                .sorted(Comparator.comparing(refusal -> refusal.code().code()))
                .toList();
        return new AuthorBundlePreflightReceipt(
                sortedRefusals.isEmpty() ? AuthorBundlePreflightStatus.ACCEPTED : AuthorBundlePreflightStatus.REFUSED,
                request.descriptor().capabilityId().value(),
                request.substrateFingerprint(),
                request.sdkCoordinate(),
                request.descriptorDigest(),
                request.artifactDigest(),
                planDigest(request),
                sortedRefusals);
    }

    private static void addGrantRefusals(
            AuthorBundlePreflightRequest request,
            List<AuthorBundlePreflightRefusal> refusals) {
        HostCredentialScope grants = request.credentialScope();
        for (CapabilityAuthorityDeclaration authority : request.descriptor().authorityDomains()) {
            if (!grants.permits(AuthorityBackendGrants.resourceClass(authority.resourceClass()))) {
                refusals.add(refusal(
                        AuthorBundlePreflightRefusalCode.GRANT_MISSING_RESOURCE_CLASS,
                        authority.resourceClass()));
            }
            if (!grants.permits(AuthorityBackendGrants.authorityDomain(authority.authorityDomain()))) {
                refusals.add(refusal(
                        AuthorBundlePreflightRefusalCode.GRANT_MISSING_AUTHORITY_DOMAIN,
                        authority.authorityDomain()));
            }
        }
    }

    private static AuthorBundlePreflightRefusal mapValidationError(CapabilityValidationError error) {
        String code = error.code();
        if (code.contains("contract.missing")) {
            return refusal(AuthorBundlePreflightRefusalCode.CONTRACT_MISSING_PROVIDER, error.detail());
        }
        if (code.contains("contract.provider.duplicate")) {
            return refusal(AuthorBundlePreflightRefusalCode.CONTRACT_DUPLICATE_PROVIDER, error.detail());
        }
        if (code.contains("scope")) {
            return refusal(AuthorBundlePreflightRefusalCode.SCOPE_UNSUPPORTED, error.detail());
        }
        return refusal(AuthorBundlePreflightRefusalCode.DESCRIPTOR_INVALID, code + ":" + error.detail());
    }

    private static String planDigest(AuthorBundlePreflightRequest request) {
        String contributions = request.contributions().stream()
                .map(contribution -> contribution.extensionPoint().wireName()
                        + ":" + contribution.scope().value()
                        + ":" + contribution.order())
                .sorted()
                .collect(Collectors.joining(","));
        String providers = request.providerClassNames().stream().sorted().collect(Collectors.joining(","));
        return AuthorityBackendDescriptorDigests.sha256Hex(
                request.descriptorDigest()
                        + "|" + request.artifactDigest()
                        + "|" + providers
                        + "|" + contributions
                        + "|" + request.substrateFingerprint()
                        + "|" + request.sdkCoordinate());
    }

    private static AuthorBundlePreflightRefusal refusal(AuthorBundlePreflightRefusalCode code, String detail) {
        return new AuthorBundlePreflightRefusal(code, detail);
    }
}
