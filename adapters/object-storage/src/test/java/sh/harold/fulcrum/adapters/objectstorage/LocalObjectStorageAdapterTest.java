package sh.harold.fulcrum.adapters.objectstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalObjectStorageAdapterTest {
    private static final String BUCKET = "artifact-store";

    @Test
    void storesAndReadsContentAddressedArtifact(@TempDir Path root) throws IOException {
        LocalObjectStorageAdapter adapter = new LocalObjectStorageAdapter(root, BUCKET);
        byte[] bytes = bytes("map-template-bytes");
        ArtifactPin pin = pin("artifact.map.validation", bytes, "map-template-v1");

        StoredObject stored = adapter.put(pin, bytes);

        assertEquals(ArtifactBlobLayout.objectAddress(BUCKET, pin), stored.address());
        assertEquals(bytes.length, stored.byteLength());
        assertTrue(adapter.exists(stored.address()));
        assertArrayEquals(bytes, adapter.read(stored.address()).orElseThrow());
    }

    @Test
    void duplicatePutIsIdempotentForIdenticalBytes(@TempDir Path root) throws IOException {
        LocalObjectStorageAdapter adapter = new LocalObjectStorageAdapter(root, BUCKET);
        byte[] bytes = bytes("same-content");
        ArtifactPin pin = pin("artifact.config.validation", bytes, "config-mode-v1");

        StoredObject first = adapter.put(pin, bytes);
        StoredObject duplicate = adapter.put(pin, bytes);

        assertEquals(first, duplicate);
        assertArrayEquals(bytes, adapter.read(first.address()).orElseThrow());
    }

    @Test
    void rejectsBytesThatDoNotMatchPinnedDigest(@TempDir Path root) {
        LocalObjectStorageAdapter adapter = new LocalObjectStorageAdapter(root, BUCKET);
        ArtifactPin pin = new ArtifactPin(
                new ArtifactId("artifact.map.validation"),
                "0".repeat(64),
                "map-template-v1");

        assertThrows(IllegalArgumentException.class, () -> adapter.put(pin, bytes("wrong-content")));
    }

    @Test
    void rejectsReadsOutsideConfiguredBucket(@TempDir Path root) {
        LocalObjectStorageAdapter adapter = new LocalObjectStorageAdapter(root, BUCKET);

        assertThrows(IllegalArgumentException.class, () -> adapter.read(new ArtifactObjectAddress(
                "object://other-bucket/artifacts/sha-256/00/00/" + "0".repeat(64) + ".blob")));
    }

    @Test
    void missingObjectReturnsEmpty(@TempDir Path root) throws IOException {
        LocalObjectStorageAdapter adapter = new LocalObjectStorageAdapter(root, BUCKET);
        byte[] bytes = bytes("missing-content");
        ArtifactPin pin = pin("artifact.missing.validation", bytes, "content-pack-v1");

        Optional<byte[]> result = adapter.read(ArtifactBlobLayout.objectAddress(BUCKET, pin));

        assertFalse(result.isPresent());
    }

    private static ArtifactPin pin(String artifactId, byte[] bytes, String compatibility) {
        return new ArtifactPin(new ArtifactId(artifactId), sha256(bytes), compatibility);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
