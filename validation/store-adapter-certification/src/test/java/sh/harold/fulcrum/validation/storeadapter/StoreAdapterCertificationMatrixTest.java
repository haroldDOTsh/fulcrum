package sh.harold.fulcrum.validation.storeadapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.StoredObject;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StoreAdapterCertificationMatrixTest {
    private static final String RESOURCE = "/fulcrum/validation/store-adapter-certification.md";

    @Test
    void matrixCoversRequiredEnginesAndProductionModules() throws IOException {
        String matrix = readMatrix();

        for (String required : List.of(
                "| kafka-log | Kafka | data/store-kafka |",
                "| cassandra-projection | Cassandra | data/store-cassandra |",
                "| postgresql-authority-record | PostgreSQL | data/store-postgresql |",
                "| valkey-cache-idempotency | Valkey | data/store-valkey |",
                "| object-storage-artifact | Object storage | adapters/object-storage |"
        )) {
            assertTrue(matrix.contains(required), "matrix missing required adapter row: " + required);
        }
    }

    @Test
    void matrixNamesAuthorityRuntimePortsAndOrderingProofs() throws IOException {
        String matrix = readMatrix();

        for (String required : List.of(
                "AuthorityCommandSource",
                "AuthorityEmissionSink",
                "AuthorityOffsetCommitter",
                "AuthorityProjectionWriter",
                "AuthorityRecordStore",
                "AuthorityDecisionRecorder",
                "offset commit after durable writes",
                "stored decision replay",
                "hot projection write before response",
                "idempotency dedupe"
        )) {
            assertTrue(matrix.contains(required), "matrix missing certification obligation: " + required);
        }
    }

    @Test
    void crossAdapterGatesPreserveTrustAndContractBoundaries() throws IOException {
        String matrix = readMatrix();

        for (String required : List.of(
                "host runtimes cannot hold canonical PostgreSQL, Cassandra write, or unrestricted Valkey credentials",
                "all schemas, topics, ACLs, and migrations come from contract declarations or generated artifacts",
                "production modules own concrete clients",
                "Testcontainers certification covers Kafka, Cassandra, PostgreSQL, Valkey, and object storage substitute paths"
        )) {
            assertTrue(matrix.contains(required), "matrix missing cross-adapter gate: " + required);
        }
    }

    @Test
    void localObjectStorageAdapterRoundTripsArtifactsAndRealmSnapshots(@TempDir Path root) throws IOException {
        LocalObjectStorageAdapter adapter = new LocalObjectStorageAdapter(root, "artifact-store");
        byte[] artifactBytes = bytes("validated-map-template");
        byte[] realmSnapshotBytes = bytes("validated-realm-snapshot");
        ArtifactPin artifactPin = pin("artifact.map.certification", artifactBytes, "map-template-v1");
        ArtifactPin realmSnapshotPin = pin("artifact.realm.snapshot.certification", realmSnapshotBytes, "realm-state-v1");

        StoredObject artifact = adapter.put(artifactPin, artifactBytes);
        StoredObject realmSnapshot = adapter.put(realmSnapshotPin, realmSnapshotBytes);

        assertEquals(ArtifactBlobLayout.objectAddress("artifact-store", artifactPin), artifact.address());
        assertEquals(ArtifactBlobLayout.objectAddress("artifact-store", realmSnapshotPin), realmSnapshot.address());
        assertArrayEquals(artifactBytes, adapter.read(artifact.address()).orElseThrow());
        assertArrayEquals(realmSnapshotBytes, adapter.read(realmSnapshot.address()).orElseThrow());
        assertTrue(adapter.exists(artifact.address()));
        assertTrue(adapter.exists(realmSnapshot.address()));
    }

    private static String readMatrix() throws IOException {
        try (var input = StoreAdapterCertificationMatrixTest.class.getResourceAsStream(RESOURCE)) {
            assertTrue(input != null, "matrix resource must exist at " + RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
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
