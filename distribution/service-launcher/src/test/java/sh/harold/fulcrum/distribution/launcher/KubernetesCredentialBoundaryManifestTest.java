package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class KubernetesCredentialBoundaryManifestTest {
    private static final List<String> MANIFESTS = List.of(
            "fulcrum/kubernetes/agones/lobby-paper-fleet.yaml",
            "fulcrum/kubernetes/velocity/lobby-velocity.yaml",
            "fulcrum/kubernetes/substrate/lobby-kafka.yaml");
    private static final List<String> CANONICAL_STORE_MARKERS = List.of(
            "FULCRUM_POSTGRES_",
            "POSTGRES_USER",
            "POSTGRES_PASSWORD",
            "fulcrum-postgres-credentials",
            "FULCRUM_CASSANDRA_",
            "CASSANDRA_USERNAME",
            "CASSANDRA_PASSWORD");
    private static final Set<String> CANONICAL_STORE_OWNER_RESOURCES = Set.of(
            "Secret/fulcrum-postgres-credentials",
            "StatefulSet/fulcrum-postgres",
            "StatefulSet/fulcrum-cassandra",
            "Job/fulcrum-authority-schema",
            "Deployment/fulcrum-authority-service");
    private static final Pattern DOCUMENT_SPLIT = Pattern.compile("(?m)^---\\s*$");
    private static final Pattern KIND = Pattern.compile("(?m)^kind:\\s*([^\\r\\n]+)\\s*$");
    private static final Pattern METADATA_NAME =
            Pattern.compile("(?m)^metadata:\\s*\\R(?:^[ \\t]+[^\\r\\n]*\\R)*?^[ \\t]+name:\\s*([^\\r\\n]+)\\s*$");

    @Test
    void hostRuntimeWorkloadsDoNotReceiveCanonicalStoreBindings() throws IOException {
        ManifestDocument paper = document("Fleet", "fulcrum-lobby-paper");
        ManifestDocument velocity = document("Deployment", "fulcrum-velocity");
        ManifestDocument worker = document("Deployment", "fulcrum-worker-agent");

        assertTrue(paper.content().contains("FULCRUM_CREDENTIAL_REF"));
        assertTrue(velocity.content().contains("FULCRUM_CREDENTIAL_REF"));
        assertTrue(worker.content().contains("FULCRUM_CREDENTIAL_REF"));
        assertNoCanonicalStoreMarkers(paper);
        assertNoCanonicalStoreMarkers(velocity);
        assertNoCanonicalStoreMarkers(worker);
    }

    @Test
    void controllerServiceKeepsCanonicalStoreBoundary() throws IOException {
        ManifestDocument controller = document("Deployment", "fulcrum-controller-service");

        assertTrue(controller.content().contains("FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS"));
        assertTrue(controller.content().contains("FULCRUM_CREDENTIAL_REF"));
        assertNoCanonicalStoreMarkers(controller);
    }

    @Test
    void canonicalStoreBindingsAreLimitedToStoreAndAuthorityOwners() throws IOException {
        for (ManifestDocument document : documents()) {
            List<String> markers = canonicalStoreMarkers(document);

            assertTrue(
                    markers.isEmpty() || CANONICAL_STORE_OWNER_RESOURCES.contains(document.resourceKey()),
                    () -> document.resourceKey() + " unexpectedly exposes canonical store bindings " + markers);
        }
    }

    private static void assertNoCanonicalStoreMarkers(ManifestDocument document) {
        List<String> markers = canonicalStoreMarkers(document);

        assertTrue(markers.isEmpty(), () -> document.resourceKey() + " exposes canonical store bindings " + markers);
    }

    private static List<String> canonicalStoreMarkers(ManifestDocument document) {
        List<String> markers = new ArrayList<>();
        for (String marker : CANONICAL_STORE_MARKERS) {
            if (document.content().contains(marker)) {
                markers.add(marker);
            }
        }
        return markers;
    }

    private static ManifestDocument document(String kind, String name) throws IOException {
        String key = kind + "/" + name;
        return documents().stream()
                .filter(document -> key.equals(document.resourceKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing manifest document " + key));
    }

    private static List<ManifestDocument> documents() throws IOException {
        List<ManifestDocument> documents = new ArrayList<>();
        for (String manifest : MANIFESTS) {
            for (String rawDocument : DOCUMENT_SPLIT.split(resource(manifest))) {
                String content = rawDocument.strip();
                if (!content.isEmpty()) {
                    documents.add(new ManifestDocument(kind(content), metadataName(content), content));
                }
            }
        }
        return List.copyOf(documents);
    }

    private static String kind(String content) {
        var matcher = KIND.matcher(content);
        if (!matcher.find()) {
            throw new AssertionError("Missing Kubernetes document kind in " + content);
        }
        return matcher.group(1).trim();
    }

    private static String metadataName(String content) {
        var matcher = METADATA_NAME.matcher(content);
        if (!matcher.find()) {
            throw new AssertionError("Missing Kubernetes document metadata.name in " + content);
        }
        return matcher.group(1).trim().replace("\"", "");
    }

    private static String resource(String name) throws IOException {
        try (var stream = KubernetesCredentialBoundaryManifestTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record ManifestDocument(String kind, String name, String content) {
        private String resourceKey() {
            return kind + "/" + name;
        }
    }
}
