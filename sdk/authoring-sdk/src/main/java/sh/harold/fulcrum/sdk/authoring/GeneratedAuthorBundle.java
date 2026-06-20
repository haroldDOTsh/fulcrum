package sh.harold.fulcrum.sdk.authoring;

import sh.harold.fulcrum.capability.api.CapabilityDescriptor;

import java.util.Map;
import java.util.Objects;

public record GeneratedAuthorBundle(
        String bundleId,
        String providerClassName,
        CapabilityDescriptor descriptor,
        String substrateFingerprint,
        Map<String, String> files) {
    public GeneratedAuthorBundle {
        bundleId = AuthoringNames.requireNonBlank(bundleId, "bundleId");
        providerClassName = AuthoringNames.requireNonBlank(providerClassName, "providerClassName");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        substrateFingerprint = AuthoringNames.requireNonBlank(substrateFingerprint, "substrateFingerprint");
        files = Map.copyOf(Objects.requireNonNull(files, "files"));
        if (files.isEmpty()) {
            throw new IllegalArgumentException("generated bundle must contain files");
        }
    }
}
