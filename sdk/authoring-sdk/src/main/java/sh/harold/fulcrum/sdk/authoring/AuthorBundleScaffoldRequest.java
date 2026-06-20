package sh.harold.fulcrum.sdk.authoring;

import sh.harold.fulcrum.capability.api.CapabilityDescriptor;

import java.util.Objects;

public record AuthorBundleScaffoldRequest(
        String bundleId,
        String packageName,
        String providerSimpleName,
        CapabilityDescriptor descriptor,
        String substrateFingerprint) {
    public AuthorBundleScaffoldRequest {
        bundleId = AuthoringNames.requireNonBlank(bundleId, "bundleId");
        packageName = AuthoringNames.requireJavaPackage(packageName, "packageName");
        providerSimpleName = AuthoringNames.requireJavaIdentifier(providerSimpleName, "providerSimpleName");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        substrateFingerprint = AuthoringNames.requireNonBlank(substrateFingerprint, "substrateFingerprint");
    }

    public String providerClassName() {
        return packageName + "." + providerSimpleName;
    }
}
