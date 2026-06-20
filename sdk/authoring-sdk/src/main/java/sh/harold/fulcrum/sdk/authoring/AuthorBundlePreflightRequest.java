package sh.harold.fulcrum.sdk.authoring;

import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.host.api.HostCredentialScope;

import java.util.List;
import java.util.Objects;

public record AuthorBundlePreflightRequest(
        CapabilityDescriptor descriptor,
        String descriptorDigest,
        String artifactDigest,
        List<String> providerClassNames,
        List<ContributionDeclaration> contributions,
        HostCredentialScope credentialScope,
        String substrateFingerprint,
        String sdkCoordinate) {
    public AuthorBundlePreflightRequest {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        descriptorDigest = AuthoringNames.requireNonBlank(descriptorDigest, "descriptorDigest");
        artifactDigest = AuthoringNames.requireNonBlank(artifactDigest, "artifactDigest");
        providerClassNames = List.copyOf(Objects.requireNonNull(providerClassNames, "providerClassNames").stream()
                .map(provider -> AuthoringNames.requireNonBlank(provider, "providerClassName"))
                .toList());
        contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        credentialScope = Objects.requireNonNull(credentialScope, "credentialScope");
        substrateFingerprint = AuthoringNames.requireNonBlank(substrateFingerprint, "substrateFingerprint");
        sdkCoordinate = AuthoringNames.requireNonBlank(sdkCoordinate, "sdkCoordinate");
    }
}
