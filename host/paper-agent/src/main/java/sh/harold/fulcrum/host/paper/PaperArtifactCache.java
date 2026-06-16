package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactDigestReference;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class PaperArtifactCache {
    private final Path cacheDirectory;
    private final ArtifactSource artifactSource;

    public PaperArtifactCache(Path cacheDirectory, ArtifactSource artifactSource) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.artifactSource = Objects.requireNonNull(artifactSource, "artifactSource");
    }

    public CachedArtifact pullVerified(ArtifactPin artifactPin) throws IOException {
        Objects.requireNonNull(artifactPin, "artifactPin");
        Files.createDirectories(cacheDirectory);

        ArtifactDigestReference expectedDigest = sha256Digest(artifactPin);
        Path cachedPath = ArtifactBlobLayout.cachePath(cacheDirectory, artifactPin);
        if (Files.exists(cachedPath)) {
            String cachedDigest = digest(Files.readAllBytes(cachedPath));
            if (expectedDigest.value().equals(cachedDigest)) {
                return new CachedArtifact(artifactPin, cachedPath, cachedDigest, true);
            }
            Files.delete(cachedPath);
        }

        byte[] artifactBytes = artifactSource.read(artifactPin.artifactId());
        String actualDigest = digest(artifactBytes);
        if (!expectedDigest.value().equals(actualDigest)) {
            throw new ArtifactVerificationException("Artifact digest mismatch for " + artifactPin.artifactId().value());
        }

        Files.createDirectories(cachedPath.getParent());
        Files.write(cachedPath, artifactBytes, StandardOpenOption.CREATE_NEW);
        return new CachedArtifact(artifactPin, cachedPath, actualDigest, false);
    }

    private static ArtifactDigestReference sha256Digest(ArtifactPin artifactPin) {
        ArtifactDigestReference digest = ArtifactBlobLayout.digestFor(artifactPin);
        if (!digest.algorithm().equals("sha-256") || digest.value().length() != 64) {
            throw new IllegalArgumentException("Paper artifact cache requires a SHA-256 digest pin");
        }
        return digest;
    }

    private static String digest(byte[] artifactBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifactBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
