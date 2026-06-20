package sh.harold.fulcrum.capability.bundle;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.util.Objects;

import java.io.IOException;

public record LoadedContribution<T>(
        ArtifactPin artifactPin,
        ContributionBundleManifest manifest,
        T provider,
        ClassLoader classLoader,
        BundleLoadDecision decision) implements AutoCloseable {
    public LoadedContribution {
        artifactPin = Objects.requireNonNull(artifactPin, "artifactPin");
        manifest = Objects.requireNonNull(manifest, "manifest");
        provider = Objects.requireNonNull(provider, "provider");
        classLoader = Objects.requireNonNull(classLoader, "classLoader");
        decision = Objects.requireNonNull(decision, "decision");
    }

    @Override
    public void close() throws IOException {
        if (classLoader instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("could not close bundle classloader", exception);
            }
        }
    }
}
