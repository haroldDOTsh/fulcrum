package sh.harold.fulcrum.adapters.objectstorage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class S3ObjectStorageAdapterTest {
    private static final String BUCKET = "artifact-store";

    @Test
    void storesAndReadsContentAddressedArtifactThroughSignedS3Requests() throws IOException {
        try (FakeS3Server server = FakeS3Server.start()) {
            S3ObjectStorageAdapter adapter = adapter(server);
            byte[] bytes = bytes("s3-map-template-bytes");
            ArtifactPin pin = pin("artifact.map.s3", bytes, "map-template-v1");

            StoredObject stored = adapter.put(pin, bytes);

            assertEquals(ArtifactBlobLayout.objectAddress(BUCKET, pin), stored.address());
            assertEquals(bytes.length, stored.byteLength());
            assertTrue(adapter.exists(stored.address()));
            assertArrayEquals(bytes, adapter.read(stored.address()).orElseThrow());
            assertTrue(server.requests().contains("HEAD /artifact-store"));
            assertTrue(server.requests().contains("PUT /artifact-store"));
            assertTrue(server.requests().contains("PUT " + objectPath(pin)));
            assertTrue(server.requests().contains("HEAD " + objectPath(pin)));
            assertTrue(server.requests().contains("GET " + objectPath(pin)));
            assertTrue(server.authorizationHeaders().stream()
                    .allMatch(header -> header.startsWith("AWS4-HMAC-SHA256 Credential=access-key/")));
        }
    }

    @Test
    void duplicatePutIsIdempotentForIdenticalBytes() throws IOException {
        try (FakeS3Server server = FakeS3Server.start()) {
            S3ObjectStorageAdapter adapter = adapter(server);
            byte[] bytes = bytes("same-s3-content");
            ArtifactPin pin = pin("artifact.config.s3", bytes, "config-mode-v1");

            StoredObject first = adapter.put(pin, bytes);
            StoredObject duplicate = adapter.put(pin, bytes);

            assertEquals(first, duplicate);
            assertArrayEquals(bytes, adapter.read(first.address()).orElseThrow());
        }
    }

    @Test
    void missingObjectReturnsEmpty() throws IOException {
        try (FakeS3Server server = FakeS3Server.start()) {
            S3ObjectStorageAdapter adapter = adapter(server);
            byte[] bytes = bytes("missing-s3-content");
            ArtifactPin pin = pin("artifact.missing.s3", bytes, "content-pack-v1");

            assertFalse(adapter.read(ArtifactBlobLayout.objectAddress(BUCKET, pin)).isPresent());
        }
    }

    @Test
    void rejectsBytesThatDoNotMatchPinnedDigest() throws IOException {
        try (FakeS3Server server = FakeS3Server.start()) {
            S3ObjectStorageAdapter adapter = adapter(server);
            ArtifactPin pin = new ArtifactPin(
                    new ArtifactId("artifact.bad.s3"),
                    "0".repeat(64),
                    "map-template-v1");

            assertThrows(IllegalArgumentException.class, () -> adapter.put(pin, bytes("wrong-content")));
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test
    void rejectsReadsOutsideConfiguredBucket() throws IOException {
        try (FakeS3Server server = FakeS3Server.start()) {
            S3ObjectStorageAdapter adapter = adapter(server);

            assertThrows(IllegalArgumentException.class, () -> adapter.read(new ArtifactObjectAddress(
                    "object://other-bucket/artifacts/sha-256/00/00/" + "0".repeat(64) + ".blob")));
        }
    }

    private static S3ObjectStorageAdapter adapter(FakeS3Server server) {
        return new S3ObjectStorageAdapter(
                server.endpoint(),
                "us-east-1",
                "access-key",
                "secret-key",
                BUCKET);
    }

    private static String objectPath(ArtifactPin pin) {
        return "/" + ArtifactBlobLayout.objectAddress(BUCKET, pin).value()
                .substring("object://".length());
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

    private static final class FakeS3Server implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final Map<String, byte[]> objects = new HashMap<>();
        private final Set<String> buckets = new HashSet<>();
        private final List<String> requests = new ArrayList<>();
        private final List<String> authorizationHeaders = new ArrayList<>();

        private FakeS3Server(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static FakeS3Server start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            FakeS3Server fake = new FakeS3Server(server, executor);
            server.createContext("/", fake::handle);
            server.setExecutor(executor);
            server.start();
            return fake;
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        List<String> authorizationHeaders() {
            return List.copyOf(authorizationHeaders);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            requests.add(method + " " + path);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || exchange.getRequestHeaders().getFirst("x-amz-date") == null
                    || exchange.getRequestHeaders().getFirst("x-amz-content-sha256") == null) {
                send(exchange, 403, bytes("missing signature"));
                return;
            }
            authorizationHeaders.add(authorization);
            String[] parts = path.substring(1).split("/", 2);
            String bucket = parts[0];
            if (parts.length == 1) {
                handleBucket(exchange, method, bucket);
                return;
            }
            handleObject(exchange, method, bucket, parts[1]);
        }

        private void handleBucket(HttpExchange exchange, String method, String bucket) throws IOException {
            if ("HEAD".equals(method)) {
                send(exchange, buckets.contains(bucket) ? 200 : 404, new byte[0]);
            } else if ("PUT".equals(method)) {
                buckets.add(bucket);
                send(exchange, 200, new byte[0]);
            } else {
                send(exchange, 405, new byte[0]);
            }
        }

        private void handleObject(HttpExchange exchange, String method, String bucket, String key)
                throws IOException {
            if (!buckets.contains(bucket)) {
                send(exchange, 404, new byte[0]);
                return;
            }
            String objectKey = bucket + "/" + key;
            if ("PUT".equals(method)) {
                objects.put(objectKey, exchange.getRequestBody().readAllBytes());
                send(exchange, 200, new byte[0]);
            } else if ("HEAD".equals(method)) {
                send(exchange, objects.containsKey(objectKey) ? 200 : 404, new byte[0]);
            } else if ("GET".equals(method)) {
                byte[] bytes = objects.get(objectKey);
                send(exchange, bytes == null ? 404 : 200, bytes == null ? new byte[0] : bytes);
            } else {
                send(exchange, 405, new byte[0]);
            }
        }

        private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
