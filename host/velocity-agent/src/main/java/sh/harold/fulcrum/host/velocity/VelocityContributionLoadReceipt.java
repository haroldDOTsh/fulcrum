package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.capability.bundle.BundleLoadDecision;
import sh.harold.fulcrum.capability.bundle.BundleLoadStatus;
import sh.harold.fulcrum.capability.bundle.BundleLoadStep;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record VelocityContributionLoadReceipt(
        String hostIdentity,
        BundleLoadStatus status,
        ArtifactPin artifactPin,
        Optional<String> bundleId,
        Optional<String> providerClassName,
        Optional<String> providerClassLoader,
        Optional<String> providerCodeSource,
        Optional<Path> cachedPath,
        List<BundleLoadStep> steps,
        Optional<String> refusalReason) {
    public VelocityContributionLoadReceipt {
        hostIdentity = requireNonBlank(hostIdentity, "hostIdentity");
        status = Objects.requireNonNull(status, "status");
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        bundleId = bundleId == null ? Optional.empty() : bundleId.map(value -> requireNonBlank(value, "bundleId"));
        providerClassName = providerClassName == null
                ? Optional.empty()
                : providerClassName.map(value -> requireNonBlank(value, "providerClassName"));
        providerClassLoader = providerClassLoader == null
                ? Optional.empty()
                : providerClassLoader.map(value -> requireNonBlank(value, "providerClassLoader"));
        providerCodeSource = providerCodeSource == null
                ? Optional.empty()
                : providerCodeSource.map(value -> requireNonBlank(value, "providerCodeSource"));
        cachedPath = cachedPath == null ? Optional.empty() : cachedPath.map(path -> path.toAbsolutePath().normalize());
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        refusalReason = refusalReason == null
                ? Optional.empty()
                : refusalReason.map(value -> requireNonBlank(value, "refusalReason"));
    }

    static VelocityContributionLoadReceipt loaded(
            String hostIdentity,
            BundleLoadDecision decision,
            String bundleId,
            Object provider) {
        Objects.requireNonNull(provider, "provider");
        Class<?> providerClass = provider.getClass();
        ClassLoader providerLoader = providerClass.getClassLoader();
        CodeSource codeSource = providerClass.getProtectionDomain().getCodeSource();
        return new VelocityContributionLoadReceipt(
                hostIdentity,
                decision.status(),
                decision.artifactPin(),
                Optional.of(bundleId),
                Optional.of(providerClass.getName()),
                Optional.of(loaderIdentity(providerLoader)),
                Optional.ofNullable(codeSource == null || codeSource.getLocation() == null
                        ? null
                        : codeSource.getLocation().toExternalForm()),
                decision.cachedPath(),
                decision.steps(),
                decision.refusalReason());
    }

    static VelocityContributionLoadReceipt refused(
            String hostIdentity,
            ArtifactPin artifactPin,
            Optional<BundleLoadDecision> decision,
            String reason) {
        BundleLoadDecision loadDecision = decision.orElse(null);
        return new VelocityContributionLoadReceipt(
                hostIdentity,
                BundleLoadStatus.REFUSED,
                artifactPin,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                loadDecision == null ? Optional.empty() : loadDecision.cachedPath(),
                loadDecision == null ? List.of(BundleLoadStep.REFUSED) : loadDecision.steps(),
                Optional.of(reason));
    }

    private static String loaderIdentity(ClassLoader classLoader) {
        if (classLoader == null) {
            return "bootstrap";
        }
        return classLoader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(classLoader));
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
