package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PaperArtifactCache {
    private static final String DIGEST_PREFIX = "sha256:";
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final Path cacheDirectory;
    private final ArtifactSource artifactSource;

    public PaperArtifactCache(Path cacheDirectory, ArtifactSource artifactSource) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.artifactSource = Objects.requireNonNull(artifactSource, "artifactSource");
    }

    public CachedArtifact pullVerified(ArtifactPin artifactPin) throws IOException {
        Objects.requireNonNull(artifactPin, "artifactPin");
        Files.createDirectories(cacheDirectory);

        String expectedDigest = normalizeDigest(artifactPin.digest());
        Path cachedPath = cacheDirectory.resolve(expectedDigest + ".artifact");
        if (Files.exists(cachedPath)) {
            String cachedDigest = digest(Files.readAllBytes(cachedPath));
            if (expectedDigest.equals(cachedDigest)) {
                return new CachedArtifact(artifactPin, cachedPath, cachedDigest, true);
            }
            Files.delete(cachedPath);
        }

        byte[] artifactBytes = artifactSource.read(artifactPin.artifactId());
        String actualDigest = digest(artifactBytes);
        if (!expectedDigest.equals(actualDigest)) {
            throw new ArtifactVerificationException("Artifact digest mismatch for " + artifactPin.artifactId().value());
        }

        Files.write(cachedPath, artifactBytes, StandardOpenOption.CREATE_NEW);
        return new CachedArtifact(artifactPin, cachedPath, actualDigest, false);
    }

    private static String normalizeDigest(String digest) {
        String checked = PaperArtifactNames.requireNonBlank(digest, "digest").toLowerCase();
        if (checked.startsWith(DIGEST_PREFIX)) {
            checked = checked.substring(DIGEST_PREFIX.length());
        }
        if (!SHA_256_HEX.matcher(checked).matches()) {
            throw new IllegalArgumentException("digest must be a SHA-256 hex value");
        }
        return checked;
    }

    private static String digest(byte[] artifactBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifactBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
