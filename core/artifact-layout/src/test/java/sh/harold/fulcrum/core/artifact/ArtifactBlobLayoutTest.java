package sh.harold.fulcrum.core.artifact;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArtifactBlobLayoutTest {
    @Test
    void buildsDeterministicObjectAddressFromDigest() {
        ArtifactPin pin = pin("artifact.map.alpha", digest('a'), "map-template-v1");

        ArtifactObjectAddress address = ArtifactBlobLayout.objectAddress("artifact-store", pin);

        assertEquals(
                "object://artifact-store/artifacts/sha-256/aa/aa/"
                        + digest('a')
                        + ".blob",
                address.value());
    }

    @Test
    void normalizesRawAndSha256DigestPinsToSameObjectAddress() {
        ArtifactPin raw = pin("artifact.map.alpha", digest('b'), "map-template-v1");
        ArtifactPin prefixed = pin("artifact.map.alpha", "sha256:" + digest('b'), "map-template-v1");

        assertEquals(
                ArtifactBlobLayout.objectAddress("artifact-store", raw),
                ArtifactBlobLayout.objectAddress("artifact-store", prefixed));
        assertEquals("sha-256:" + digest('b'), ArtifactBlobLayout.digestFor(prefixed).wireValue());
    }

    @Test
    void rejectsUnsafeBucketAndMalformedDigest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactBlobLayout.objectAddress("bad/bucket", pin("artifact.map.alpha", digest('c'), "map-template-v1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactBlobLayout.objectAddress("artifact-store", pin("artifact.map.alpha", "../escape", "map-template-v1")));
    }

    @Test
    void cachePathIncludesArtifactPinIdentityUnderDigestShard() {
        Path root = Path.of("build/test-cache");
        ArtifactPin first = pin("artifact.map.alpha", digest('d'), "map-template-v1");
        ArtifactPin differentCompatibility = pin("artifact.map.alpha", digest('d'), "map-template-v2");
        ArtifactPin differentArtifact = pin("artifact.map.beta", digest('d'), "map-template-v1");

        Path firstPath = ArtifactBlobLayout.cachePath(root, first);
        Path compatibilityPath = ArtifactBlobLayout.cachePath(root, differentCompatibility);
        Path artifactPath = ArtifactBlobLayout.cachePath(root, differentArtifact);

        assertTrue(firstPath.startsWith(root.toAbsolutePath().normalize()));
        assertTrue(firstPath.toString().contains(Path.of("sha-256", "dd", "dd", digest('d')).toString()));
        assertNotEquals(firstPath, compatibilityPath);
        assertNotEquals(firstPath, artifactPath);
    }

    private static ArtifactPin pin(String artifactId, String digest, String compatibility) {
        return new ArtifactPin(new ArtifactId(artifactId), digest, compatibility);
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
