package sh.harold.fulcrum.capability.bundle;

import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactDigestReference;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.artifact.ArtifactVerificationStep;
import sh.harold.fulcrum.core.artifact.VerifiedArtifact;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.jar.JarFile;

public final class ContributionBundleLoader {
    private final String objectBucket;
    private final Path cacheRoot;
    private final BundleArtifactSource artifactSource;

    public ContributionBundleLoader(String objectBucket, Path cacheRoot, BundleArtifactSource artifactSource) {
        this.objectBucket = BundleNames.requireNonBlank(objectBucket, "objectBucket");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        this.artifactSource = Objects.requireNonNull(artifactSource, "artifactSource");
    }

    public VerifiedContributionBundle verify(
            ArtifactPin artifactPin,
            String expectedDescriptorDigest,
            CapabilityMaterializationPlan plan) {
        Objects.requireNonNull(artifactPin, "artifactPin");
        String checkedDescriptorDigest = BundleNames.requireNonBlank(expectedDescriptorDigest, "expectedDescriptorDigest");
        Objects.requireNonNull(plan, "plan");
        List<BundleLoadStep> steps = new ArrayList<>();
        ArtifactObjectAddress address = ArtifactBlobLayout.objectAddress(objectBucket, artifactPin);
        Path cachedPath = ArtifactBlobLayout.cachePath(cacheRoot, artifactPin);
        try {
            Files.createDirectories(cachedPath.getParent());
            if (Files.exists(cachedPath)) {
                steps.add(BundleLoadStep.CACHE_HIT);
                verifyDigest(artifactPin, Files.readAllBytes(cachedPath));
            } else {
                byte[] bytes = artifactSource.read(address)
                        .orElseThrow(() -> refused("bundle artifact not found at " + address.value(), artifactPin, address, cachedPath, steps));
                steps.add(BundleLoadStep.PULLED);
                verifyDigest(artifactPin, bytes);
                Path staged = Files.createTempFile(cachedPath.getParent(), cachedPath.getFileName().toString(), ".staged");
                Files.write(staged, bytes);
                Files.move(staged, cachedPath, StandardCopyOption.ATOMIC_MOVE);
            }
            steps.add(BundleLoadStep.VERIFIED);
            return verifyCachedBundle(artifactPin, address, cachedPath, checkedDescriptorDigest, plan, steps);
        } catch (IOException exception) {
            throw new BundleLoadException("could not verify bundle artifact", exception);
        }
    }

    public VerifiedContributionBundle verifyResolved(
            VerifiedArtifact verifiedArtifact,
            String expectedDescriptorDigest,
            CapabilityMaterializationPlan plan) {
        Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
        String checkedDescriptorDigest = BundleNames.requireNonBlank(expectedDescriptorDigest, "expectedDescriptorDigest");
        Objects.requireNonNull(plan, "plan");
        ArtifactPin artifactPin = verifiedArtifact.artifactPin();
        ArtifactObjectAddress address = ArtifactBlobLayout.objectAddress(objectBucket, artifactPin);
        Path cachedPath = verifiedArtifact.cachedPath();
        List<BundleLoadStep> steps = new ArrayList<>();
        steps.add(BundleLoadStep.SOURCE_NORMALIZED);
        boolean signatureVerified = verifiedArtifact.verificationReceipt().steps().contains(ArtifactVerificationStep.SIGNATURE_VERIFIED);
        boolean unsignedLocalAccepted = verifiedArtifact.verificationReceipt().steps().contains(ArtifactVerificationStep.UNSIGNED_LOCAL_IMPORT_ACCEPTED);
        if (signatureVerified) {
            steps.add(BundleLoadStep.SIGNATURE_VERIFIED);
        }
        if (!signatureVerified && !unsignedLocalAccepted) {
            throw refused("bundle artifact signature verification missing", artifactPin, address, cachedPath, steps);
        }
        try {
            verifyDigest(artifactPin, Files.readAllBytes(cachedPath));
            steps.add(BundleLoadStep.VERIFIED);
            return verifyCachedBundle(artifactPin, address, cachedPath, checkedDescriptorDigest, plan, steps);
        } catch (IOException exception) {
            throw new BundleLoadException("could not verify resolved bundle artifact", exception);
        }
    }

    public <T> LoadedContribution<T> load(
            VerifiedContributionBundle bundle,
            Class<T> serviceType) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(serviceType, "serviceType");
        try {
            URL[] urls = new URL[]{bundle.cachedPath().toUri().toURL()};
            URLClassLoader classLoader = new URLClassLoader(urls, serviceType.getClassLoader());
            try {
                List<T> providers = ServiceLoader.load(serviceType, classLoader).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList();
                List<T> bundleProviders = providers.stream()
                        .filter(provider -> provider.getClass().getClassLoader() == classLoader)
                        .toList();
                List<T> accepted = bundleProviders.stream()
                        .filter(provider -> bundle.manifest().providerClassNames().contains(provider.getClass().getName()))
                        .toList();
                if (bundleProviders.size() != accepted.size()
                        || accepted.size() != bundle.manifest().providerClassNames().size()) {
                    closeQuietly(classLoader);
                    throw new BundleLoadException("bundle providers did not match verified manifest");
                }
                return new LoadedContribution<>(
                        bundle.artifactPin(),
                        bundle.manifest(),
                        accepted.getFirst(),
                        classLoader,
                        loadedDecision(bundle));
            } catch (RuntimeException exception) {
                closeQuietly(classLoader);
                throw exception;
            }
        } catch (IOException exception) {
            throw new BundleLoadException("could not create bundle classloader", exception);
        }
    }

    private static ContributionBundleManifest readManifest(Path cachedPath) throws IOException {
        try (JarFile jarFile = new JarFile(cachedPath.toFile())) {
            var entry = jarFile.getJarEntry(ContributionBundleManifest.RESOURCE_PATH);
            if (entry == null) {
                throw new BundleLoadException("bundle manifest missing");
            }
            try (InputStream inputStream = jarFile.getInputStream(entry)) {
                return ContributionBundleManifest.read(inputStream);
            }
        }
    }

    private static boolean materializationContains(
            CapabilityMaterializationPlan plan,
            ContributionBundleManifest manifest) {
        List<ContributionDeclaration> declarations = plan.contributions().stream()
                .map(CapabilityMaterializationPlan.ContributionRegistration::declaration)
                .toList();
        return manifest.contributions().stream().allMatch(requirement -> declarations.stream().anyMatch(declaration ->
                declaration.extensionPoint() == requirement.extensionPoint()
                        && declaration.scope().equals(requirement.scope())
                        && declaration.order() == requirement.order()));
    }

    private static VerifiedContributionBundle verifyCachedBundle(
            ArtifactPin artifactPin,
            ArtifactObjectAddress address,
            Path cachedPath,
            String expectedDescriptorDigest,
            CapabilityMaterializationPlan plan,
            List<BundleLoadStep> steps) throws IOException {
        ContributionBundleManifest manifest = readManifest(cachedPath);
        steps.add(BundleLoadStep.MANIFEST_PARSED);
        if (!manifest.descriptorDigest().equals(expectedDescriptorDigest)) {
            throw refused("bundle descriptor digest mismatch", artifactPin, address, cachedPath, steps);
        }
        if (!materializationContains(plan, manifest)) {
            throw refused("bundle contributions are absent from materialization plan", artifactPin, address, cachedPath, steps);
        }
        steps.add(BundleLoadStep.MATERIALIZATION_MATCHED);

        return new VerifiedContributionBundle(
                artifactPin,
                address,
                cachedPath,
                manifest,
                verifiedDecision(artifactPin, address, cachedPath, steps));
    }

    private static void verifyDigest(ArtifactPin artifactPin, byte[] bytes) {
        String expectedDigest = normalizedDigest(artifactPin);
        String actualDigest = sha256(bytes);
        if (!expectedDigest.equals(actualDigest)) {
            throw new BundleLoadException("bundle artifact digest mismatch");
        }
    }

    private static String normalizedDigest(ArtifactPin artifactPin) {
        ArtifactDigestReference digest = ArtifactBlobLayout.digestFor(artifactPin);
        if (!digest.algorithm().equals("sha-256")) {
            throw new IllegalArgumentException("bundle artifacts require sha-256 pins");
        }
        return digest.value();
    }

    private static BundleLoadException refused(
            String reason,
            ArtifactPin artifactPin,
            ArtifactObjectAddress address,
            Path cachedPath,
            List<BundleLoadStep> steps) {
        steps.add(BundleLoadStep.REFUSED);
        BundleLoadDecision ignored = new BundleLoadDecision(
                BundleLoadStatus.REFUSED,
                artifactPin,
                address,
                Optional.of(cachedPath),
                steps,
                Optional.of(reason));
        return new BundleLoadException(ignored);
    }

    private static BundleLoadDecision verifiedDecision(
            ArtifactPin artifactPin,
            ArtifactObjectAddress address,
            Path cachedPath,
            List<BundleLoadStep> steps) {
        return new BundleLoadDecision(
                BundleLoadStatus.VERIFIED,
                artifactPin,
                address,
                Optional.of(cachedPath),
                steps,
                Optional.empty());
    }

    private static BundleLoadDecision loadedDecision(VerifiedContributionBundle bundle) {
        List<BundleLoadStep> loadedSteps = new ArrayList<>(bundle.decision().steps());
        loadedSteps.add(BundleLoadStep.PROVIDER_LOADED);
        return new BundleLoadDecision(
                BundleLoadStatus.LOADED,
                bundle.artifactPin(),
                bundle.objectAddress(),
                Optional.of(bundle.cachedPath()),
                loadedSteps,
                Optional.empty());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static void closeQuietly(URLClassLoader classLoader) {
        try {
            classLoader.close();
        } catch (IOException ignored) {
            // Preserve the original load failure.
        }
    }
}
