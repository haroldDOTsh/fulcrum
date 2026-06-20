package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AuthorityBackendRegistrationRequest(
        CapabilityDescriptor descriptor,
        Optional<HostSecurityContext> securityContext,
        String descriptorDigest,
        String bundleDigest,
        String sdkVersion,
        Instant requestedAt) {
    public AuthorityBackendRegistrationRequest {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        securityContext = securityContext == null ? Optional.empty() : securityContext;
        descriptorDigest = AuthoritySdkNames.requireNonBlank(descriptorDigest, "descriptorDigest");
        bundleDigest = AuthoritySdkNames.requireNonBlank(bundleDigest, "bundleDigest");
        sdkVersion = AuthoritySdkNames.requireNonBlank(sdkVersion, "sdkVersion");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public static AuthorityBackendRegistrationRequest credentialed(
            CapabilityDescriptor descriptor,
            HostSecurityContext securityContext,
            String bundleDigest,
            Instant requestedAt) {
        return new AuthorityBackendRegistrationRequest(
                descriptor,
                Optional.of(Objects.requireNonNull(securityContext, "securityContext")),
                AuthorityBackendDescriptorDigests.descriptorDigest(descriptor),
                bundleDigest,
                AuthoritySdkVersion.CURRENT,
                requestedAt);
    }

    public static AuthorityBackendRegistrationRequest uncredentialed(
            CapabilityDescriptor descriptor,
            String bundleDigest,
            Instant requestedAt) {
        return new AuthorityBackendRegistrationRequest(
                descriptor,
                Optional.empty(),
                AuthorityBackendDescriptorDigests.descriptorDigest(descriptor),
                bundleDigest,
                AuthoritySdkVersion.CURRENT,
                requestedAt);
    }
}
