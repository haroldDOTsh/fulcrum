package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.host.api.HostMenuContribution;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

final class PaperContributionBundleCatalog {
    private static final String DEFAULT_BUCKET = "artifact-store";

    private final Path bundleDirectory;
    private final String hostIdentity;

    PaperContributionBundleCatalog(Path bundleDirectory, String hostIdentity) {
        this.bundleDirectory = Objects.requireNonNull(bundleDirectory, "bundleDirectory")
                .toAbsolutePath()
                .normalize();
        this.hostIdentity = PaperArtifactNames.requireNonBlank(hostIdentity, "hostIdentity");
    }

    List<PaperLoadedContribution<HostMenuContribution>> loadMenuContributions() {
        if (!Files.isDirectory(bundleDirectory)) {
            return List.of();
        }
        try (var paths = Files.list(bundleDirectory)) {
            List<PaperLoadedContribution<HostMenuContribution>> loaded = new ArrayList<>();
            for (Path declarationFile : paths
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.naturalOrder())
                    .toList()) {
                loaded.add(loadMenuContribution(declarationFile));
            }
            return List.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read Paper contribution bundle catalog", exception);
        }
    }

    private PaperLoadedContribution<HostMenuContribution> loadMenuContribution(Path declarationFile) {
        BundleDeclaration declaration = BundleDeclaration.read(bundleDirectory, declarationFile);
        PaperContributionBundleBootstrap bootstrap = new PaperContributionBundleBootstrap(
                hostIdentity,
                declaration.objectBucket(),
                declaration.cacheRoot(),
                address -> address.equals(ArtifactBlobLayout.objectAddress(
                        declaration.objectBucket(),
                        declaration.artifactPin()))
                        ? Optional.of(Files.readAllBytes(declaration.artifactFile()))
                        : Optional.empty());
        return bootstrap.load(declaration.paperDeclaration(), HostMenuContribution.class);
    }

    private record BundleDeclaration(
            ArtifactPin artifactPin,
            String descriptorDigest,
            List<ContributionDeclaration> contributions,
            String objectBucket,
            Path cacheRoot,
            Path artifactFile) {
        private BundleDeclaration {
            artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
            descriptorDigest = PaperArtifactNames.requireNonBlank(descriptorDigest, "descriptorDigest");
            contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
            if (contributions.isEmpty()) {
                throw new IllegalArgumentException("contributions must not be empty");
            }
            objectBucket = PaperArtifactNames.requireNonBlank(objectBucket, "objectBucket");
            cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
            artifactFile = Objects.requireNonNull(artifactFile, "artifactFile").toAbsolutePath().normalize();
        }

        static BundleDeclaration read(Path bundleDirectory, Path declarationFile) {
            Properties properties = new Properties();
            try (InputStream inputStream = Files.newInputStream(declarationFile)) {
                properties.load(inputStream);
            } catch (IOException exception) {
                throw new IllegalStateException("could not read Paper contribution bundle declaration " + declarationFile, exception);
            }
            ArtifactPin pin = new ArtifactPin(
                    new ArtifactId(required(properties, "artifact.id")),
                    required(properties, "artifact.digest"),
                    required(properties, "artifact.compatibility"));
            Path artifactFile = bundleDirectory.resolve(optional(
                    properties,
                    "artifact.file",
                    pin.artifactId().value() + ".jar"));
            return new BundleDeclaration(
                    pin,
                    required(properties, "descriptor.digest"),
                    split(required(properties, "contributions")).stream()
                            .map(BundleDeclaration::contribution)
                            .toList(),
                    optional(properties, "object.bucket", DEFAULT_BUCKET),
                    bundleDirectory.resolve(optional(properties, "cache.dir", ".cache")),
                    artifactFile);
        }

        PaperContributionBundleDeclaration paperDeclaration() {
            return new PaperContributionBundleDeclaration(
                    artifactPin,
                    descriptorDigest,
                    materializationPlan());
        }

        private CapabilityMaterializationPlan materializationPlan() {
            CapabilityDescriptor descriptor = new CapabilityDescriptor(
                    new CapabilityId("paper-contribution-" + artifactPin.artifactId().value()),
                    new CapabilityVersion("0.0.1"),
                    List.of(),
                    List.of(),
                    List.of(),
                    contributions,
                    contributions.stream()
                            .map(ContributionDeclaration::scope)
                            .distinct()
                            .toList());
            return CapabilityMaterializationPlanner.plan(List.of(descriptor));
        }

        private static ContributionDeclaration contribution(String value) {
            String checked = PaperArtifactNames.requireNonBlank(value, "contribution");
            int firstSeparator = checked.indexOf(':');
            int lastSeparator = checked.lastIndexOf(':');
            if (firstSeparator <= 0 || lastSeparator <= firstSeparator) {
                throw new IllegalArgumentException("contribution must use extensionPoint:scope:order");
            }
            String extensionPointValue = checked.substring(0, firstSeparator);
            CapabilityExtensionPoint extensionPoint = Arrays.stream(CapabilityExtensionPoint.values())
                    .filter(candidate -> candidate.wireName().equals(extensionPointValue)
                            || candidate.name().equals(extensionPointValue))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown extension point: " + extensionPointValue));
            return new ContributionDeclaration(
                    extensionPoint,
                    new CapabilityScope(checked.substring(firstSeparator + 1, lastSeparator)),
                    Integer.parseInt(checked.substring(lastSeparator + 1)));
        }

        private static String required(Properties properties, String key) {
            return PaperArtifactNames.requireNonBlank(properties.getProperty(key), key);
        }

        private static String optional(Properties properties, String key, String fallback) {
            String value = properties.getProperty(key);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static List<String> split(String value) {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(entry -> !entry.isEmpty())
                    .toList();
        }
    }
}
