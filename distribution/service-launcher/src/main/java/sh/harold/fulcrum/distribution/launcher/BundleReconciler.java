package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BundleReconciler {
    private final BundleArtifactVerificationPort artifactVerification;
    private final BundleInstallGrantIssuer grantIssuer;
    private final BundleInstallGrantStateStore grantStateStore;
    private final BundleInstanceSupervisor instanceSupervisor;
    private final BundleContributionSupervisor contributionSupervisor;
    private final Clock clock;

    BundleReconciler(
            BundleArtifactVerificationPort artifactVerification,
            BundleInstallGrantIssuer grantIssuer,
            BundleInstallGrantStateStore grantStateStore,
            BundleInstanceSupervisor instanceSupervisor,
            BundleContributionSupervisor contributionSupervisor,
            Clock clock) {
        this.artifactVerification = java.util.Objects.requireNonNull(artifactVerification, "artifactVerification");
        this.grantIssuer = java.util.Objects.requireNonNull(grantIssuer, "grantIssuer");
        this.grantStateStore = java.util.Objects.requireNonNull(grantStateStore, "grantStateStore");
        this.instanceSupervisor = java.util.Objects.requireNonNull(instanceSupervisor, "instanceSupervisor");
        this.contributionSupervisor = java.util.Objects.requireNonNull(contributionSupervisor, "contributionSupervisor");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    List<BundleReconcileReceipt> reconcile(
            BundleDesiredState desiredState,
            BundleReconcileAuthorization authorization) {
        List<BundleReconcileReceipt> receipts = new ArrayList<>();
        for (DeclaredBundle bundle : desiredState.bundles()) {
            if (!bundle.enabled()) {
                receipts.add(removalReceipt(bundle));
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
            if (bundle.kind().equals("contribution")) {
                BundleContributionInstallReceipt contributionReceipt = contributionSupervisor.install(
                        bundle,
                        grant.orElseThrow(),
                        evidence.orElseThrow(),
                        clock.instant());
                BundleInstallGrantLifecycleReceipt grantLifecycle = grantStateStore.recordInstall(
                        bundle,
                        grant.orElseThrow(),
                        contributionReceipt.status(),
                        clock.instant());
                receipts.add(BundleReconcileReceipt.contributionInstalled(
                        bundle,
                        grant.orElseThrow(),
                        evidence.orElseThrow(),
                        contributionReceipt,
                        grantLifecycle,
                        clock.instant()));
                continue;
            }
            BundleInstanceStartReceipt startReceipt = instanceSupervisor.start(
                    bundle,
                    grant.orElseThrow(),
                    evidence.orElseThrow(),
                    clock.instant());
            BundleInstallGrantLifecycleReceipt grantLifecycle = grantStateStore.recordInstall(
                    bundle,
                    grant.orElseThrow(),
                    startReceipt.status(),
                    clock.instant());
            receipts.add(BundleReconcileReceipt.installed(
                    bundle,
                    grant.orElseThrow(),
                    evidence.orElseThrow(),
                    startReceipt,
                    grantLifecycle,
                    clock.instant()));
        }
        return List.copyOf(receipts);
    }

    BundleReconcileReceipt reconcileRemoval(String bundleId) {
        BundleInstanceRemovalReceipt instanceRemoval = instanceSupervisor.remove(bundleId, clock.instant());
        BundleContributionRemovalReceipt contributionRemoval = contributionSupervisor.remove(bundleId, clock.instant());
        BundleInstallGrantLifecycleReceipt grantRevocation = revokeGrantIfRemoved(
                bundleId,
                instanceRemoval.removed() && contributionRemoval.removed(),
                clock.instant());
        return BundleReconcileReceipt.removed(
                bundleId,
                instanceRemoval,
                contributionRemoval,
                grantRevocation,
                clock.instant());
    }

    private BundleReconcileReceipt removalReceipt(DeclaredBundle bundle) {
        if (bundle.kind().equals("contribution")) {
            BundleContributionRemovalReceipt contributionRemoval =
                    contributionSupervisor.remove(bundle.id(), clock.instant());
            BundleInstallGrantLifecycleReceipt grantRevocation = revokeGrantIfRemoved(
                    bundle.id(),
                    contributionRemoval.removed(),
                    clock.instant());
            return BundleReconcileReceipt.contributionRemoved(
                    bundle.id(),
                    contributionRemoval,
                    grantRevocation,
                    clock.instant());
        }
        BundleInstanceRemovalReceipt instanceRemoval = instanceSupervisor.remove(bundle.id(), clock.instant());
        BundleInstallGrantLifecycleReceipt grantRevocation = revokeGrantIfRemoved(
                bundle.id(),
                instanceRemoval.removed(),
                clock.instant());
        return BundleReconcileReceipt.removed(
                bundle.id(),
                instanceRemoval,
                grantRevocation,
                clock.instant());
    }

    private BundleInstallGrantLifecycleReceipt revokeGrantIfRemoved(
            String bundleId,
            boolean removed,
            java.time.Instant now) {
        if (!removed) {
            return grantStateStore.latest(bundleId)
                    .map(BundleInstallGrantLifecycleReceipt::fromRecord)
                    .orElseGet(() -> BundleInstallGrantLifecycleReceipt.absent("grant-revocation-blocked"));
        }
        return grantStateStore.revoke(bundleId, "bundle-removed-grant-revoked", now);
    }
}
