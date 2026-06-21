package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Instant;
import java.util.Optional;

record BundleReconcileReceipt(
        String bundleId,
        String status,
        String reason,
        String digest,
        Optional<String> grantFingerprint,
        Optional<String> artifactVerificationEvidence,
        Instant reconciledAt) {
    static BundleReconcileReceipt installed(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence evidence,
            Instant now) {
        return new BundleReconcileReceipt(
                bundle.id(),
                "INSTALLED",
                "declared-state-satisfied",
                bundle.digest(),
                Optional.of(grant.grantFingerprint()),
                Optional.of(evidence.wireValue()),
                now);
    }

    static BundleReconcileReceipt denied(DeclaredBundle bundle, String reason, Instant now) {
        return new BundleReconcileReceipt(
                bundle.id(),
                "DENIED",
                reason,
                bundle.digest(),
                Optional.empty(),
                Optional.empty(),
                now);
    }

    static BundleReconcileReceipt removed(String bundleId, Instant now) {
        return new BundleReconcileReceipt(
                bundleId,
                "REMOVED",
                "declaration-removed-grant-revoked",
                "none",
                Optional.empty(),
                Optional.empty(),
                now);
    }
}
