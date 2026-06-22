package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Instant;

interface BundleContributionSupervisor {
    BundleContributionInstallReceipt install(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence artifactVerification,
            Instant now);

    BundleContributionRemovalReceipt remove(String bundleId, Instant now);
}
