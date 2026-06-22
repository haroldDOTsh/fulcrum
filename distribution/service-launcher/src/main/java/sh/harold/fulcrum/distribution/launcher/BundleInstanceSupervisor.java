package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Instant;

interface BundleInstanceSupervisor {
    BundleInstanceStartReceipt start(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence artifactVerification,
            Instant now);

    BundleInstanceRemovalReceipt remove(String bundleId, Instant now);
}
