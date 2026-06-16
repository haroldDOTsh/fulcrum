package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperArtifactCacheTest {
    private static final ArtifactId ARTIFACT_ID = new ArtifactId("artifact-map-template-1");

    @TempDir
    private Path cacheDirectory;

    @Test
    void pullsVerifiesAndCachesArtifactByDigest() throws IOException {
        byte[] artifactBytes = bytes("bounded-map-template");
        AtomicInteger reads = new AtomicInteger();
        PaperArtifactCache cache = new PaperArtifactCache(cacheDirectory, source(Map.of(ARTIFACT_ID, artifactBytes), reads));
        ArtifactPin pin = new ArtifactPin(ARTIFACT_ID, sha256(artifactBytes), "map-template-v1");

        CachedArtifact firstPull = cache.pullVerified(pin);
        CachedArtifact secondPull = cache.pullVerified(pin);

        assertFalse(firstPull.cacheHit());
        assertTrue(secondPull.cacheHit());
        assertEquals(firstPull.cachedPath(), secondPull.cachedPath());
        assertEquals(1, reads.get());
        assertArrayEquals(artifactBytes, Files.readAllBytes(firstPull.cachedPath()));
    }

    @Test
    void rejectsDigestMismatchWithoutCachingArtifact() {
        byte[] artifactBytes = bytes("bounded-map-template");
        PaperArtifactCache cache = new PaperArtifactCache(
                cacheDirectory,
                source(Map.of(ARTIFACT_ID, artifactBytes), new AtomicInteger()));
        ArtifactPin wrongDigest = new ArtifactPin(ARTIFACT_ID, sha256(bytes("other-template")), "map-template-v1");

        assertThrows(ArtifactVerificationException.class, () -> cache.pullVerified(wrongDigest));

        assertTrue(cacheDirectoryIsEmpty());
    }

    @Test
    void rejectsUnsafeDigestBeforeBuildingCachePath() {
        PaperArtifactCache cache = new PaperArtifactCache(
                cacheDirectory,
                source(Map.of(ARTIFACT_ID, bytes("bounded-map-template")), new AtomicInteger()));
        ArtifactPin unsafeDigest = new ArtifactPin(ARTIFACT_ID, "../escape", "map-template-v1");

        assertThrows(IllegalArgumentException.class, () -> cache.pullVerified(unsafeDigest));

        assertTrue(cacheDirectoryIsEmpty());
    }

    @Test
    void corruptCacheEntryIsDiscardedAndRefetched() throws IOException {
        byte[] artifactBytes = bytes("bounded-map-template");
        AtomicInteger reads = new AtomicInteger();
        PaperArtifactCache cache = new PaperArtifactCache(cacheDirectory, source(Map.of(ARTIFACT_ID, artifactBytes), reads));
        ArtifactPin pin = new ArtifactPin(ARTIFACT_ID, "sha256:" + sha256(artifactBytes), "map-template-v1");
        CachedArtifact firstPull = cache.pullVerified(pin);
        Files.writeString(firstPull.cachedPath(), "corrupt");

        CachedArtifact secondPull = cache.pullVerified(pin);

        assertFalse(secondPull.cacheHit());
        assertEquals(2, reads.get());
        assertArrayEquals(artifactBytes, Files.readAllBytes(secondPull.cachedPath()));
    }

    private static ArtifactSource source(Map<ArtifactId, byte[]> artifacts, AtomicInteger reads) {
        return artifactId -> {
            reads.incrementAndGet();
            byte[] artifactBytes = artifacts.get(artifactId);
            if (artifactBytes == null) {
                throw new IOException("Artifact not found: " + artifactId.value());
            }
            return artifactBytes;
        };
    }

    private boolean cacheDirectoryIsEmpty() {
        try (var files = Files.list(cacheDirectory)) {
            return files.findAny().isEmpty();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect artifact cache directory", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
