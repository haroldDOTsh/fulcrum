package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BundleReconciler {
    private final BundleArtifactVerificationPort artifactVerification;
    private final BundleInstallGrantIssuer grantIssuer;
    private final Clock clock;

    BundleReconciler(
            BundleArtifactVerificationPort artifactVerification,
            BundleInstallGrantIssuer grantIssuer,
            Clock clock) {
        this.artifactVerification = java.util.Objects.requireNonNull(artifactVerification, "artifactVerification");
        this.grantIssuer = java.util.Objects.requireNonNull(grantIssuer, "grantIssuer");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    List<BundleReconcileReceipt> reconcile(
            BundleDesiredState desiredState,
            BundleReconcileAuthorization authorization) {
        List<BundleReconcileReceipt> receipts = new ArrayList<>();
        for (DeclaredBundle bundle : desiredState.bundles()) {
            if (!bundle.enabled()) {
                receipts.add(BundleReconcileReceipt.removed(bundle.id(), clock.instant()));
                continue;
            }
            Optional<AuthorityArtifactVerificationEvidence> evidence = artifactVerification.verify(bundle);
            if (evidence.isEmpty()
                    || !evidence.orElseThrow().verified()
                    || !evidence.orElseThrow().digest().equals(bundle.digest())) {
                receipts.add(BundleReconcileReceipt.denied(bundle, "ARTIFACT_VERIFICATION_FAILED", clock.instant()));
                continue;
            }
            Optional<IssuedBundleGrant> grant = grantIssuer.issue(bundle, authorization);
            if (grant.isEmpty()) {
                receipts.add(BundleReconcileReceipt.denied(bundle, "GRANT_NOT_AUTHORIZED", clock.instant()));
                continue;
            }
            receipts.add(BundleReconcileReceipt.installed(
                    bundle,
                    grant.orElseThrow(),
                    evidence.orElseThrow(),
                    clock.instant()));
        }
        return List.copyOf(receipts);
    }

    BundleReconcileReceipt reconcileRemoval(String bundleId) {
        return BundleReconcileReceipt.removed(bundleId, clock.instant());
    }
}
