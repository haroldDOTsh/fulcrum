package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.capability.bundle.BundleArtifactSource;
import sh.harold.fulcrum.capability.bundle.BundleLoadException;
import sh.harold.fulcrum.capability.bundle.ContributionBundleLoader;
import sh.harold.fulcrum.capability.bundle.LoadedContribution;
import sh.harold.fulcrum.capability.bundle.VerifiedContributionBundle;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class PaperContributionBundleBootstrap {
    private final String hostIdentity;
    private final ContributionBundleLoader loader;

    public PaperContributionBundleBootstrap(
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

    PaperContributionBundleBootstrap(String hostIdentity, ContributionBundleLoader loader) {
        this.hostIdentity = PaperArtifactNames.requireNonBlank(hostIdentity, "hostIdentity");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public <T> PaperLoadedContribution<T> load(
            PaperContributionBundleDeclaration declaration,
            Class<T> serviceType) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(serviceType, "serviceType");
        try {
            VerifiedContributionBundle verified = loader.verify(
                    declaration.artifactPin(),
                    declaration.expectedDescriptorDigest(),
                    declaration.materializationPlan());
            LoadedContribution<T> loaded = loader.load(verified, serviceType);
            return new PaperLoadedContribution<>(
                    loaded,
                    PaperContributionLoadReceipt.loaded(
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

    private PaperContributionBundleLoadException refused(
            PaperContributionBundleDeclaration declaration,
            Optional<sh.harold.fulcrum.capability.bundle.BundleLoadDecision> decision,
            String reason,
            RuntimeException cause) {
        return new PaperContributionBundleLoadException(
                PaperContributionLoadReceipt.refused(
                        hostIdentity,
                        declaration.artifactPin(),
                        decision,
                        reason == null ? cause.getClass().getName() : reason),
                cause);
    }
}
