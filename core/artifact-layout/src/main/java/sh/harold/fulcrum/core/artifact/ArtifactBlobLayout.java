package sh.harold.fulcrum.core.artifact;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ArtifactBlobLayout {
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,62}");
    private static final String ARTIFACT_PREFIX = "artifacts";

    private ArtifactBlobLayout() {
    }

    public static ArtifactDigestReference digestFor(ArtifactPin artifactPin) {
        Objects.requireNonNull(artifactPin, "artifactPin");
        return ArtifactDigestReference.parse(artifactPin.digest());
    }

    public static ArtifactObjectAddress objectAddress(String bucket, ArtifactPin artifactPin) {
        String checkedBucket = ArtifactLayoutNames.requireNonBlank(bucket, "bucket").toLowerCase();
        if (!BUCKET.matcher(checkedBucket).matches() || checkedBucket.contains("..")) {
            throw new IllegalArgumentException("bucket must be a stable object store bucket name");
        }
        ArtifactDigestReference digest = digestFor(artifactPin);
        String value = digest.value();
        return new ArtifactObjectAddress("object://"
                + checkedBucket
                + "/"
                + ARTIFACT_PREFIX
                + "/"
                + digest.algorithm()
                + "/"
                + value.substring(0, 2)
                + "/"
                + value.substring(2, 4)
                + "/"
                + value
                + ".blob");
    }

    public static Path cachePath(Path cacheRoot, ArtifactPin artifactPin) {
        Path root = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        ArtifactDigestReference digest = digestFor(artifactPin);
        String value = digest.value();
        Path path = root
                .resolve(digest.algorithm())
                .resolve(value.substring(0, 2))
                .resolve(value.substring(2, 4))
                .resolve(value)
                .resolve(pinIdentity(artifactPin, digest) + ".artifact")
                .normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("artifact cache path escaped the cache root");
        }
        return path;
    }

    private static String pinIdentity(ArtifactPin artifactPin, ArtifactDigestReference digest) {
        String identity = artifactPin.artifactId().value()
                + "\u0000"
                + artifactPin.compatibility()
                + "\u0000"
                + digest.wireValue();
        return sha256(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
