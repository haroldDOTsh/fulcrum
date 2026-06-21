package sh.harold.fulcrum.control.registration;

import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationClient;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRejectionReason;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CapabilityBackendRegistrationController implements AuthorityBackendRegistrationClient {
    private final Map<String, AuthorityBackendRegistrationReceipt> receiptsByAuthorityDomain = new HashMap<>();
    private long nextFencingEpoch = 1;

    @Override
    public AuthorityBackendRegistrationReceipt register(AuthorityBackendRegistrationRequest request) {
        AuthorityBackendRegistrationRequest checked = Objects.requireNonNull(request, "request");
        CapabilityDescriptor descriptor = checked.descriptor();
        Optional<HostSecurityContext> maybeSecurityContext = checked.securityContext();

        if (maybeSecurityContext.isEmpty()) {
            return denied(checked, AuthorityBackendRegistrationRejectionReason.MISSING_CREDENTIAL, Optional.empty(), Optional.empty());
        }

        HostSecurityContext securityContext = maybeSecurityContext.orElseThrow();
        String grantFingerprint = AuthorityBackendDescriptorDigests.grantFingerprint(securityContext.credentialScope());
        String expectedDescriptorDigest = AuthorityBackendDescriptorDigests.descriptorDigest(descriptor);
        if (!expectedDescriptorDigest.equals(checked.descriptorDigest())) {
            return denied(
                    checked,
                    AuthorityBackendRegistrationRejectionReason.INVALID_DESCRIPTOR_DIGEST,
                    maybeSecurityContext,
                    Optional.of(grantFingerprint));
        }

        Optional<AuthorityArtifactVerificationEvidence> artifactVerification = checked.artifactVerification();
        if (artifactVerification.isEmpty()
                || !artifactVerification.orElseThrow().verified()
                || !artifactVerification.orElseThrow().digest().equals(checked.bundleDigest())) {
            return denied(
                    checked,
                    AuthorityBackendRegistrationRejectionReason.ARTIFACT_VERIFICATION_FAILED,
                    maybeSecurityContext,
                    Optional.of(grantFingerprint));
        }

        List<CapabilityAuthorityDeclaration> authorities = descriptor.authorityDomains();
        if (authorities.isEmpty()) {
            return denied(
                    checked,
                    AuthorityBackendRegistrationRejectionReason.NO_AUTHORITY_DOMAIN,
                    maybeSecurityContext,
                    Optional.of(grantFingerprint));
        }

        for (CapabilityAuthorityDeclaration authority : authorities) {
            if (!securityContext.credentialScope().permits(AuthorityBackendGrants.authorityDomain(authority.authorityDomain()))) {
                return denied(
                        checked,
                        AuthorityBackendRegistrationRejectionReason.MISSING_AUTHORITY_DOMAIN_GRANT,
                        maybeSecurityContext,
                        Optional.of(grantFingerprint));
            }
            if (!securityContext.credentialScope().permits(AuthorityBackendGrants.resourceClass(authority.resourceClass()))) {
                return denied(
                        checked,
                        AuthorityBackendRegistrationRejectionReason.MISSING_RESOURCE_CLASS_GRANT,
                        maybeSecurityContext,
                        Optional.of(grantFingerprint));
            }
        }

        CapabilityValidationResult validation = CapabilityMaterializationPlanner.validate(List.of(descriptor));
        if (!validation.valid()) {
            return denied(
                    checked,
                    AuthorityBackendRegistrationRejectionReason.MATERIALIZATION_CONFLICT,
                    maybeSecurityContext,
                    Optional.of(grantFingerprint));
        }

        Optional<AuthorityBackendRegistrationReceipt> existing = existingReceipt(authorities);
        if (existing.isPresent()) {
            AuthorityBackendRegistrationReceipt receipt = existing.orElseThrow();
            if (sameRegistration(receipt, checked, securityContext, grantFingerprint)
                    && allAuthoritiesAlreadyBound(authorities, receipt)) {
                return receipt;
            }
            return denied(
                    checked,
                    AuthorityBackendRegistrationRejectionReason.AUTHORITY_DOMAIN_CONFLICT,
                    maybeSecurityContext,
                    Optional.of(grantFingerprint));
        }

        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(List.of(descriptor));
        AuthorityBackendRegistrationReceipt receipt = admitted(checked, securityContext, grantFingerprint, plan);
        for (CapabilityAuthorityDeclaration authority : authorities) {
            receiptsByAuthorityDomain.put(authority.authorityDomain(), receipt);
        }
        return receipt;
    }

    private Optional<AuthorityBackendRegistrationReceipt> existingReceipt(
            List<CapabilityAuthorityDeclaration> authorities) {
        return authorities.stream()
                .map(authority -> receiptsByAuthorityDomain.get(authority.authorityDomain()))
                .filter(Objects::nonNull)
                .findFirst();
    }

    private boolean allAuthoritiesAlreadyBound(
            List<CapabilityAuthorityDeclaration> authorities,
            AuthorityBackendRegistrationReceipt receipt) {
        return authorities.stream()
                .allMatch(authority -> receipt.equals(receiptsByAuthorityDomain.get(authority.authorityDomain())));
    }

    private static boolean sameRegistration(
            AuthorityBackendRegistrationReceipt receipt,
            AuthorityBackendRegistrationRequest request,
            HostSecurityContext securityContext,
            String grantFingerprint) {
        return receipt.descriptorDigest().equals(request.descriptorDigest())
                && receipt.bundleDigest().equals(request.bundleDigest())
                && receipt.artifactVerificationEvidence()
                .equals(request.artifactVerification().map(AuthorityArtifactVerificationEvidence::wireValue))
                && receipt.principalId().filter(securityContext.identity().principalId()::equals).isPresent()
                && receipt.grantFingerprint().filter(grantFingerprint::equals).isPresent();
    }

    private AuthorityBackendRegistrationReceipt admitted(
            AuthorityBackendRegistrationRequest request,
            HostSecurityContext securityContext,
            String grantFingerprint,
            CapabilityMaterializationPlan plan) {
        long fencingEpoch = nextFencingEpoch++;
        String planHash = materializationPlanHash(plan);
        String receiptId = receiptId(request, fencingEpoch, AuthorityBackendRegistrationStatus.ADMITTED.name());
        String signature = signature(
                AuthorityBackendRegistrationStatus.ADMITTED,
                request,
                planHash,
                Optional.of(securityContext),
                Optional.of(grantFingerprint),
                fencingEpoch,
                receiptId);
        return new AuthorityBackendRegistrationReceipt(
                AuthorityBackendRegistrationStatus.ADMITTED,
                request.descriptor().capabilityId(),
                request.descriptorDigest(),
                request.bundleDigest(),
                planHash,
                Optional.of(securityContext.identity().principalId()),
                Optional.of(grantFingerprint),
                fencingEpoch,
                request.requestedAt(),
                receiptId,
                Optional.empty(),
                request.artifactVerification().map(AuthorityArtifactVerificationEvidence::wireValue),
                signature);
    }

    private static AuthorityBackendRegistrationReceipt denied(
            AuthorityBackendRegistrationRequest request,
            AuthorityBackendRegistrationRejectionReason reason,
            Optional<HostSecurityContext> securityContext,
            Optional<String> grantFingerprint) {
        String receiptId = receiptId(request, 0, reason.name());
        String signature = signature(
                AuthorityBackendRegistrationStatus.DENIED,
                request,
                "none",
                securityContext,
                grantFingerprint,
                0,
                receiptId);
        return new AuthorityBackendRegistrationReceipt(
                AuthorityBackendRegistrationStatus.DENIED,
                request.descriptor().capabilityId(),
                request.descriptorDigest(),
                request.bundleDigest(),
                "none",
                securityContext.map(context -> context.identity().principalId()),
                grantFingerprint,
                0,
                request.requestedAt(),
                receiptId,
                Optional.of(reason),
                request.artifactVerification().map(AuthorityArtifactVerificationEvidence::wireValue),
                signature);
    }

    private static String materializationPlanHash(CapabilityMaterializationPlan plan) {
        return AuthorityBackendDescriptorDigests.sha256Hex(Objects.requireNonNull(plan, "plan").toString());
    }

    private static String receiptId(
            AuthorityBackendRegistrationRequest request,
            long fencingEpoch,
            String statusOrReason) {
        return "backend-registration-"
                + AuthorityBackendDescriptorDigests.sha256Hex(
                request.descriptor().capabilityId().value()
                        + "|" + request.descriptorDigest()
                        + "|" + request.bundleDigest()
                        + "|" + fencingEpoch
                        + "|" + statusOrReason)
                .substring(0, 16);
    }

    private static String signature(
            AuthorityBackendRegistrationStatus status,
            AuthorityBackendRegistrationRequest request,
            String materializationPlanHash,
            Optional<HostSecurityContext> securityContext,
            Optional<String> grantFingerprint,
            long fencingEpoch,
            String receiptId) {
        return AuthorityBackendDescriptorDigests.sha256Hex(
                "status=" + status
                        + "|capabilityId=" + request.descriptor().capabilityId().value()
                        + "|descriptorDigest=" + request.descriptorDigest()
                        + "|bundleDigest=" + request.bundleDigest()
                        + "|artifactVerificationEvidence=" + request.artifactVerification()
                        .map(AuthorityArtifactVerificationEvidence::wireValue)
                        .orElse("none")
                        + "|materializationPlanHash=" + materializationPlanHash
                        + "|principalId=" + securityContext.map(context -> context.identity().principalId().value()).orElse("none")
                        + "|grantFingerprint=" + grantFingerprint.orElse("none")
                        + "|fencingEpoch=" + fencingEpoch
                        + "|issuedAt=" + request.requestedAt()
                        + "|receiptId=" + receiptId);
    }
}
