package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.CapabilityId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AuthorityBackendRegistrationReceipt(
        AuthorityBackendRegistrationStatus status,
        CapabilityId capabilityId,
        String descriptorDigest,
        String bundleDigest,
        String materializationPlanHash,
        Optional<PrincipalId> principalId,
        Optional<String> grantFingerprint,
        long fencingEpoch,
        Instant issuedAt,
        String receiptId,
        Optional<AuthorityBackendRegistrationRejectionReason> rejectionReason,
        Optional<String> artifactVerificationEvidence,
        String signature) {
    public AuthorityBackendRegistrationReceipt {
        status = Objects.requireNonNull(status, "status");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId");
        descriptorDigest = AuthoritySdkNames.requireNonBlank(descriptorDigest, "descriptorDigest");
        bundleDigest = AuthoritySdkNames.requireNonBlank(bundleDigest, "bundleDigest");
        materializationPlanHash = AuthoritySdkNames.requireNonBlank(materializationPlanHash, "materializationPlanHash");
        principalId = principalId == null ? Optional.empty() : principalId;
        grantFingerprint = grantFingerprint == null ? Optional.empty() : grantFingerprint
                .map(fingerprint -> AuthoritySdkNames.requireNonBlank(fingerprint, "grantFingerprint"));
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        receiptId = AuthoritySdkNames.requireNonBlank(receiptId, "receiptId");
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        artifactVerificationEvidence = artifactVerificationEvidence == null ? Optional.empty() : artifactVerificationEvidence
                .map(evidence -> AuthoritySdkNames.requireNonBlank(evidence, "artifactVerificationEvidence"));
        signature = AuthoritySdkNames.requireNonBlank(signature, "signature");
        if (status == AuthorityBackendRegistrationStatus.ADMITTED && principalId.isEmpty()) {
            throw new IllegalArgumentException("admitted receipt must include principalId");
        }
        if (status == AuthorityBackendRegistrationStatus.ADMITTED && artifactVerificationEvidence.isEmpty()) {
            throw new IllegalArgumentException("admitted receipt must include artifactVerificationEvidence");
        }
        if (status == AuthorityBackendRegistrationStatus.DENIED && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("denied receipt must include rejectionReason");
        }
    }

    public boolean admitted() {
        return status == AuthorityBackendRegistrationStatus.ADMITTED;
    }

    public String wireValue() {
        return "status=" + status
                + "|capabilityId=" + capabilityId.value()
                + "|descriptorDigest=" + descriptorDigest
                + "|bundleDigest=" + bundleDigest
                + "|materializationPlanHash=" + materializationPlanHash
                + "|principalId=" + principalId.map(PrincipalId::value).orElse("none")
                + "|grantFingerprint=" + grantFingerprint.orElse("none")
                + "|fencingEpoch=" + fencingEpoch
                + "|issuedAt=" + issuedAt
                + "|receiptId=" + receiptId
                + "|rejectionReason=" + rejectionReason.map(Enum::name).orElse("none")
                + "|artifactVerificationEvidence=" + artifactVerificationEvidence.orElse("none");
    }

    public String signedWireValue() {
        return wireValue() + "|signature=" + signature;
    }
}
