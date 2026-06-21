package sh.harold.fulcrum.validation.authoritysdk;

import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationClient;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRuntimeGuard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class NoopAuthorityBackend {
    public static final String AUTHORITY_DOMAIN = "noop.authority";
    public static final String RESOURCE_CLASS = "external-authority";
    public static final String BUNDLE_DIGEST = "sha256:no-op-backend";

    private final AuthorityBackendRegistrationClient registrationClient;
    private final HostSecurityContext securityContext;
    private final Instant requestedAt;

    public NoopAuthorityBackend(
            AuthorityBackendRegistrationClient registrationClient,
            HostSecurityContext securityContext,
            Instant requestedAt) {
        this.registrationClient = Objects.requireNonNull(registrationClient, "registrationClient");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public AuthorityBackendRegistrationReceipt register() {
        return registrationClient.register(AuthorityBackendRegistrationRequest.credentialed(
                descriptor(),
                securityContext,
                BUNDLE_DIGEST,
                AuthorityArtifactVerificationEvidence.verified(
                        "OCI",
                        "oci://ghcr.io/sh-harold/noop-backend@sha256:" + BUNDLE_DIGEST,
                        BUNDLE_DIGEST,
                        "cosign:test"),
                requestedAt));
    }

    public AuthorityBackendRegistrationReceipt start() {
        return AuthorityBackendRuntimeGuard.requireAdmitted(register());
    }

    public static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                new CapabilityId("noop-backend"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(new CapabilityAuthorityDeclaration(AUTHORITY_DOMAIN, RESOURCE_CLASS, 1)),
                List.of(new ContributionDeclaration(CapabilityExtensionPoint.PAPER_COMMANDS, CapabilityScope.NETWORK, 0)),
                List.of(CapabilityScope.NETWORK));
    }
}
