package sh.harold.fulcrum.control.registration;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRejectionReason;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;
import sh.harold.fulcrum.sdk.authority.HttpAuthorityBackendRegistrationClient;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapabilityBackendRegistrationControllerTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final String DOMAIN = "noop.authority";
    private static final String RESOURCE_CLASS = "external-authority";
    private static final String BUNDLE_DIGEST = "sha256:no-op-bundle";
    private static final PrincipalId PRINCIPAL = new PrincipalId("backend-noop");

    @Test
    void admitsCredentialedDescriptorAndReplaysSameRegistration() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        CapabilityDescriptor descriptor = descriptor("noop-backend", DOMAIN);
        AuthorityBackendRegistrationRequest request = AuthorityBackendRegistrationRequest.credentialed(
                descriptor,
                securityContext(authorityGrants(DOMAIN, RESOURCE_CLASS)),
                BUNDLE_DIGEST,
                verification(BUNDLE_DIGEST),
                NOW);

        AuthorityBackendRegistrationReceipt admitted = controller.register(request);
        AuthorityBackendRegistrationReceipt replayed = controller.register(request);

        assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, admitted.status());
        assertEquals(new CapabilityId("noop-backend"), admitted.capabilityId());
        assertEquals(Optional.of(PRINCIPAL), admitted.principalId());
        assertEquals(1, admitted.fencingEpoch());
        assertEquals(admitted, replayed);
        assertTrue(admitted.materializationPlanHash().matches("[0-9a-f]{64}"));
        assertTrue(admitted.signature().matches("[0-9a-f]{64}"));
    }

    @Test
    void deniesMissingCredentialWithReceipt() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        CapabilityDescriptor descriptor = descriptor("noop-backend", DOMAIN);
        AuthorityBackendRegistrationRequest request = AuthorityBackendRegistrationRequest.uncredentialed(
                descriptor,
                BUNDLE_DIGEST,
                NOW);

        AuthorityBackendRegistrationReceipt receipt = controller.register(request);

        assertEquals(AuthorityBackendRegistrationStatus.DENIED, receipt.status());
        assertEquals(Optional.of(AuthorityBackendRegistrationRejectionReason.MISSING_CREDENTIAL), receipt.rejectionReason());
        assertEquals(Optional.empty(), receipt.principalId());
        assertEquals(0, receipt.fencingEpoch());
    }

    @Test
    void deniesMissingAuthorityDomainGrantBeforeAdmission() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        CapabilityDescriptor descriptor = descriptor("noop-backend", DOMAIN);
        AuthorityBackendRegistrationRequest request = AuthorityBackendRegistrationRequest.credentialed(
                descriptor,
                securityContext(Set.of(AuthorityBackendGrants.resourceClass(RESOURCE_CLASS))),
                BUNDLE_DIGEST,
                verification(BUNDLE_DIGEST),
                NOW);

        AuthorityBackendRegistrationReceipt receipt = controller.register(request);

        assertEquals(AuthorityBackendRegistrationStatus.DENIED, receipt.status());
        assertEquals(Optional.of(AuthorityBackendRegistrationRejectionReason.MISSING_AUTHORITY_DOMAIN_GRANT), receipt.rejectionReason());
        assertEquals(0, receipt.fencingEpoch());
    }

    @Test
    void deniesInvalidDescriptorDigest() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        CapabilityDescriptor descriptor = descriptor("noop-backend", DOMAIN);
        AuthorityBackendRegistrationRequest request = new AuthorityBackendRegistrationRequest(
                descriptor,
                Optional.of(securityContext(authorityGrants(DOMAIN, RESOURCE_CLASS))),
                AuthorityBackendDescriptorDigests.sha256Hex("wrong-descriptor"),
                BUNDLE_DIGEST,
                Optional.of(verification(BUNDLE_DIGEST)),
                "0.1.0-SNAPSHOT",
                NOW);

        AuthorityBackendRegistrationReceipt receipt = controller.register(request);

        assertEquals(AuthorityBackendRegistrationStatus.DENIED, receipt.status());
        assertEquals(Optional.of(AuthorityBackendRegistrationRejectionReason.INVALID_DESCRIPTOR_DIGEST), receipt.rejectionReason());
    }

    @Test
    void deniesAuthorityDomainConflict() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        HostSecurityContext securityContext = securityContext(authorityGrants(DOMAIN, RESOURCE_CLASS));
        AuthorityBackendRegistrationReceipt first = controller.register(AuthorityBackendRegistrationRequest.credentialed(
                descriptor("noop-backend", DOMAIN),
                securityContext,
                BUNDLE_DIGEST,
                verification(BUNDLE_DIGEST),
                NOW));

        AuthorityBackendRegistrationReceipt conflicting = controller.register(AuthorityBackendRegistrationRequest.credentialed(
                descriptor("other-backend", DOMAIN),
                securityContext,
                "sha256:other-bundle",
                verification("sha256:other-bundle"),
                NOW.plusSeconds(1)));

        assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, first.status());
        assertEquals(AuthorityBackendRegistrationStatus.DENIED, conflicting.status());
        assertEquals(Optional.of(AuthorityBackendRegistrationRejectionReason.AUTHORITY_DOMAIN_CONFLICT), conflicting.rejectionReason());
    }

    @Test
    void httpServerAdmitsCredentialedBackendThroughNeutralEndpoint() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        AuthorityBackendRegistrationRequest request = AuthorityBackendRegistrationRequest.credentialed(
                descriptor("noop-backend", DOMAIN),
                securityContext(authorityGrants(DOMAIN, RESOURCE_CLASS)),
                BUNDLE_DIGEST,
                verification(BUNDLE_DIGEST),
                NOW);

        try (CapabilityBackendRegistrationHttpServer server = CapabilityBackendRegistrationHttpServer.start(
                new InetSocketAddress("127.0.0.1", 0),
                controller)) {
            AuthorityBackendRegistrationReceipt receipt = new HttpAuthorityBackendRegistrationClient(
                    server.endpointUri())
                    .register(request);

            assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, receipt.status());
            assertEquals(Optional.of(PRINCIPAL), receipt.principalId());
            assertEquals(1, receipt.fencingEpoch());
        }
    }

    @Test
    void deniesMissingArtifactVerificationBeforeAdmission() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        AuthorityBackendRegistrationRequest request = AuthorityBackendRegistrationRequest.credentialed(
                descriptor("noop-backend", DOMAIN),
                securityContext(authorityGrants(DOMAIN, RESOURCE_CLASS)),
                BUNDLE_DIGEST,
                NOW);

        AuthorityBackendRegistrationReceipt receipt = controller.register(request);

        assertEquals(AuthorityBackendRegistrationStatus.DENIED, receipt.status());
        assertEquals(Optional.of(AuthorityBackendRegistrationRejectionReason.ARTIFACT_VERIFICATION_FAILED), receipt.rejectionReason());
        assertEquals(0, receipt.fencingEpoch());
    }

    private static CapabilityDescriptor descriptor(String capabilityId, String authorityDomain) {
        return new CapabilityDescriptor(
                new CapabilityId(capabilityId),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(new CapabilityAuthorityDeclaration(authorityDomain, RESOURCE_CLASS, 1)),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }

    private static Set<HostResourceGrant> authorityGrants(String authorityDomain, String resourceClass) {
        return Set.of(
                AuthorityBackendGrants.authorityDomain(authorityDomain),
                AuthorityBackendGrants.resourceClass(resourceClass));
    }

    private static AuthorityArtifactVerificationEvidence verification(String digest) {
        return AuthorityArtifactVerificationEvidence.verified(
                "OCI",
                "oci://ghcr.io/sh-harold/test@sha256:" + digest,
                digest,
                "cosign:test");
    }

    private static HostSecurityContext securityContext(Set<HostResourceGrant> grants) {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-noop-backend"),
                        "authority-backend",
                        new PoolId("pool-authority"),
                        new MachineRef("machine-authority"),
                        PRINCIPAL),
                "service-account:noop-backend",
                new HostCredentialScope(grants));
    }
}
