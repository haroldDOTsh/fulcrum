package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.capability.bundle.BundleArtifactSource;
import sh.harold.fulcrum.capability.bundle.BundleLoadDecision;
import sh.harold.fulcrum.capability.bundle.BundleLoadException;
import sh.harold.fulcrum.capability.bundle.ContributionBundleLoader;
import sh.harold.fulcrum.capability.bundle.LoadedContribution;
import sh.harold.fulcrum.capability.bundle.VerifiedContributionBundle;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class VelocityContributionBundleBootstrap {
    private final String hostIdentity;
    private final ContributionBundleLoader loader;

    public VelocityContributionBundleBootstrap(
            String hostIdentity,
            String objectBucket,
            Path cacheRoot,
            BundleArtifactSource artifactSource) {
        this(
                hostIdentity,
                new ContributionBundleLoader(
                        objectBucket,
                        Objects.requireNonNull(cacheRoot, "cacheRoot"),
                        Objects.requireNonNull(artifactSource, "artifactSource")));
    }

    VelocityContributionBundleBootstrap(String hostIdentity, ContributionBundleLoader loader) {
        this.hostIdentity = requireNonBlank(hostIdentity, "hostIdentity");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public <T> VelocityLoadedContribution<T> load(
            VelocityContributionBundleDeclaration declaration,
            Class<T> serviceType) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(serviceType, "serviceType");
        try {
            VerifiedContributionBundle verified = loader.verify(
                    declaration.artifactPin(),
                    declaration.expectedDescriptorDigest(),
                    declaration.materializationPlan());
            LoadedContribution<T> loaded = loader.load(verified, serviceType);
            return new VelocityLoadedContribution<>(
                    loaded,
                    VelocityContributionLoadReceipt.loaded(
                            hostIdentity,
                            loaded.decision(),
                            loaded.manifest().bundleId(),
                            loaded.provider()));
        } catch (BundleLoadException exception) {
            throw refused(declaration, exception.decision(), exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw refused(declaration, Optional.empty(), exception.getMessage(), exception);
        }
    }

    private VelocityContributionBundleLoadException refused(
            VelocityContributionBundleDeclaration declaration,
            Optional<BundleLoadDecision> decision,
            String reason,
            RuntimeException cause) {
        return new VelocityContributionBundleLoadException(
                VelocityContributionLoadReceipt.refused(
                        hostIdentity,
                        declaration.artifactPin(),
                        decision,
                        reason == null ? cause.getClass().getName() : reason),
                cause);
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return trimmed;
    }
}
