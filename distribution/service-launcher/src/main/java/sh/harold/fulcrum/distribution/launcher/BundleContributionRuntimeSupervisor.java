package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.bundle.BundleLoadDecision;
import sh.harold.fulcrum.capability.bundle.BundleLoadException;
import sh.harold.fulcrum.capability.bundle.ContributionBundleLoader;
import sh.harold.fulcrum.capability.bundle.VerifiedContributionBundle;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class BundleContributionRuntimeSupervisor implements BundleContributionSupervisor {
    private static final String CONTRIBUTION_KIND = "contribution";
    private static final String BUCKET = "artifact-store";
    private static final String ARTIFACT_COMPATIBILITY = "fulcrum-bundle-v1";

    private final BundleContributionStateStore stateStore;
    private final ContributionBundleLoader loader;

    BundleContributionRuntimeSupervisor(
            BundleContributionStateStore stateStore,
            ContributionBundleLoader loader) {
        this.stateStore = java.util.Objects.requireNonNull(stateStore, "stateStore");
        this.loader = java.util.Objects.requireNonNull(loader, "loader");
    }

    static BundleContributionRuntimeSupervisor authorDev(Path stateDir) {
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                stateDir.resolve("author-dev").resolve("objects"),
                BUCKET);
        return new BundleContributionRuntimeSupervisor(
                new BundleContributionStateStore(stateDir),
                new ContributionBundleLoader(
                        BUCKET,
                        stateDir.resolve("author-dev").resolve("cache"),
                        objectStorage::read));
    }

    @Override
    public BundleContributionInstallReceipt install(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence artifactVerification,
            Instant now) {
        Optional<BundleContributionRecord> latest = stateStore.latest(bundle.id());
        if (latest.filter(record -> sameStagedContribution(record, bundle, grant)).isPresent()) {
            BundleContributionRecord record = latest.orElseThrow();
            return BundleContributionInstallReceipt.staged(
                    "contribution-already-staged",
                    record.cachePath().orElseThrow(),
                    record.loadEvidence().orElseThrow());
        }
        BundleContributionInstallReceipt receipt = installReceipt(bundle);
        stateStore.append(BundleContributionRecord.installed(bundle, grant, receipt, now));
        return receipt;
    }

    private static boolean sameStagedContribution(
            BundleContributionRecord record,
            DeclaredBundle bundle,
            IssuedBundleGrant grant) {
        return record.status().equals("STAGED")
                && record.digest().equals(bundle.digest())
                && record.cachePath().isPresent()
                && record.loadEvidence().isPresent()
                && record.grantFingerprint().filter(grant.grantFingerprint()::equals).isPresent();
    }

    private BundleContributionInstallReceipt installReceipt(DeclaredBundle bundle) {
        if (!bundle.kind().equals(CONTRIBUTION_KIND)) {
            return BundleContributionInstallReceipt.blocked("UNSUPPORTED_CONTRIBUTION_BUNDLE_KIND", Optional.empty());
        }
        if (bundle.descriptorDigest().isEmpty()) {
            return BundleContributionInstallReceipt.blocked("CONTRIBUTION_DESCRIPTOR_DIGEST_REQUIRED", Optional.empty());
        }
        if (bundle.contributions().isEmpty()) {
            return BundleContributionInstallReceipt.blocked("CONTRIBUTION_DECLARATIONS_REQUIRED", Optional.empty());
        }
        try {
            VerifiedContributionBundle verified = loader.verify(
                    artifactPin(bundle),
                    bundle.descriptorDigest().orElseThrow(),
                    CapabilityMaterializationPlanner.plan(List.of(capabilityDescriptor(bundle))));
            return BundleContributionInstallReceipt.staged(
                    verified.cachedPath().toString(),
                    decisionEvidence(verified.decision()));
        } catch (BundleLoadException exception) {
            return BundleContributionInstallReceipt.blocked(
                    exception.decision()
                            .flatMap(BundleLoadDecision::refusalReason)
                            .orElse(exception.getMessage()),
                    exception.decision().map(BundleContributionRuntimeSupervisor::decisionEvidence));
        }
    }

    @Override
    public BundleContributionRemovalReceipt remove(String bundleId, Instant now) {
        BundleContributionRecord latest = stateStore.latestByBundle().get(bundleId);
        if (latest == null || latest.status().equals("REMOVED")) {
            return BundleContributionRemovalReceipt.removed("contribution-already-absent");
        }
        stateStore.append(latest.removed(now));
        return BundleContributionRemovalReceipt.removed("contribution-staging-record-removed-grant-revoked");
    }

    private static ArtifactPin artifactPin(DeclaredBundle bundle) {
        return new ArtifactPin(
                new ArtifactId("artifact.bundle." + bundle.id()),
                bundle.digest(),
                ARTIFACT_COMPATIBILITY);
    }

    private static CapabilityDescriptor capabilityDescriptor(DeclaredBundle bundle) {
        return new CapabilityDescriptor(
                new CapabilityId(bundle.id()),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(),
                bundle.contributions(),
                List.of(new CapabilityScope(bundle.scope())));
    }

    private static String decisionEvidence(BundleLoadDecision decision) {
        String steps = decision.steps().stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(","));
        return "loadStatus=" + decision.status().name()
                + "|steps=" + steps
                + "|object=" + decision.objectAddress().value()
                + "|cache=" + decision.cachedPath().map(Path::toString).orElse("none")
                + decision.refusalReason().map(reason -> "|refusal=" + reason).orElse("");
    }
}
