package sh.harold.fulcrum.adapters.agones.allocator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceKinds;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgonesAllocatorRestClientTest {
    @TempDir
    private Path tempDir;

    @Test
    void allocationPostsReadySelectorAndMapsClaim() throws Exception {
        String responseBody = allocationResponse("paper");
        try (AllocatorFixture fixture = AllocatorFixture.responding(200, responseBody)) {
            HostAllocationRequest request = allocationRequest();

            HostAllocationClaim claim = new AgonesAllocatorRestClient(fixture.uri(), "default").allocate(request);

            assertEquals("POST", fixture.method());
            assertEquals("application/json", fixture.contentType());
            String body = fixture.requestBody();
            assertTrue(body.contains("\"namespace\":\"default\""));
            assertTrue(body.contains("\"gameServerState\":\"Ready\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/pool-id\":\"pool-paper-small\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/session-id\":\"session-host-1\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/slot-id\":\"slot-host-1\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/resolved-manifest-id\":\"manifest-paper-1\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/trace-id\":\"trace-host-1\""));

            assertEquals("slot-host-1", claim.slotId().value());
            assertEquals(request.sessionId(), claim.sessionId());
            assertEquals(request.resolvedManifestId(), claim.resolvedManifestId());
            assertEquals(request.traceEnvelope(), claim.traceEnvelope());
            assertEquals(request.requestedAt(), claim.allocatedAt());
            assertEquals("10.244.0.17", claim.minecraftEndpoint().host());
            assertEquals(31_565, claim.minecraftEndpoint().port());
            assertEquals("agones-gameserver-1", claim.instanceIdentity().instanceId().value());
            assertEquals(HostInstanceKinds.PAPER, claim.instanceIdentity().instanceKind());
            assertEquals("pool-paper-small", claim.instanceIdentity().poolId().value());
            assertEquals("machine-agones-a", claim.instanceIdentity().machineRef().value());
            assertEquals("principal-paper-agones-1", claim.instanceIdentity().principalId().value());
        }
    }

    @Test
    void nonSuccessResponseFailsAllocation() throws Exception {
        try (AllocatorFixture fixture = AllocatorFixture.responding(409, "{\"error\":\"none ready\"}")) {
            AgonesAllocatorRestClient client = new AgonesAllocatorRestClient(fixture.uri(), "default");

            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.allocate(allocationRequest()));

            assertTrue(exception.getMessage().contains("HTTP 409"));
        }
    }

    @Test
    void missingStaticIdentityMetadataFailsAllocation() throws Exception {
        String responseBody = """
                {
                  "gameServerName": "agones-gameserver-1",
                  "nodeName": "machine-agones-a",
                  "address": "10.244.0.17",
                  "ports": [
                    {"name": "minecraft", "port": 31565}
                  ],
                  "metadata": {
                    "annotations": {
                      "sh.harold.fulcrum/instance-kind": "paper",
                      "sh.harold.fulcrum/principal-id": "principal-paper-agones-1"
                    }
                  }
                }
                """;
        try (AllocatorFixture fixture = AllocatorFixture.responding(200, responseBody)) {
            AgonesAllocatorRestClient client = new AgonesAllocatorRestClient(fixture.uri(), "default");

            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.allocate(allocationRequest()));

            assertTrue(exception.getMessage().contains(AgonesAllocatorJson.SLOT_ID_ANNOTATION));
        }
    }

    @Test
    void missingMinecraftPortFailsAllocation() throws Exception {
        String responseBody = """
                {
                  "gameServerName": "agones-gameserver-1",
                  "nodeName": "machine-agones-a",
                  "address": "10.244.0.17",
                  "ports": [
                    {"name": "metrics", "port": 9090}
                  ],
                  "metadata": {
                    "annotations": {
                      "sh.harold.fulcrum/instance-id": "instance-paper-agones-1",
                      "sh.harold.fulcrum/slot-id": "slot-agones-1",
                      "sh.harold.fulcrum/instance-kind": "paper",
                      "sh.harold.fulcrum/principal-id": "principal-paper-agones-1"
                    }
                  }
                }
                """;
        try (AllocatorFixture fixture = AllocatorFixture.responding(200, responseBody)) {
            AgonesAllocatorRestClient client = new AgonesAllocatorRestClient(fixture.uri(), "default");

            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.allocate(allocationRequest()));

            assertTrue(exception.getMessage().contains("minecraft"));
        }
    }

    @Test
    void nonPaperAllocationFailsAllocation() throws Exception {
        try (AllocatorFixture fixture = AllocatorFixture.responding(200, allocationResponse("worker"))) {
            AgonesAllocatorRestClient client = new AgonesAllocatorRestClient(fixture.uri(), "default");

            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.allocate(allocationRequest()));

            assertTrue(exception.getMessage().contains("non-Paper"));
        }
    }

    @Test
    void mtlsHttpClientLoadsKubernetesStylePemFiles() throws Exception {
        Path cert = tempDir.resolve("tls.crt");
        Path key = tempDir.resolve("tls.key");
        Path ca = tempDir.resolve("tls-ca.crt");
        Files.writeString(cert, TEST_CERTIFICATE, StandardCharsets.US_ASCII);
        Files.writeString(key, TEST_PRIVATE_KEY, StandardCharsets.US_ASCII);
        Files.writeString(ca, TEST_CERTIFICATE, StandardCharsets.US_ASCII);

        HttpClient client = AgonesAllocatorRestClient.mtlsHttpClient(cert, key, ca);

        assertNotNull(client);
    }

    private static HostAllocationRequest allocationRequest() {
        return new HostAllocationRequest(
                new PoolId("pool-paper-small"),
                new SessionId("session-host-1"),
                new ResolvedManifestId("manifest-paper-1"),
                new TraceEnvelope(
                        "trace-host-1",
                        "span-host-1",
                        Optional.empty(),
                        Instant.parse("2026-06-16T12:00:00Z"),
                        "test-control",
                        new InstanceId("instance-controller-1")),
                Instant.parse("2026-06-16T12:00:01Z"));
    }

    private static String allocationResponse(String instanceKind) {
        return """
                {
                  "gameServerName": "agones-gameserver-1",
                  "nodeName": "machine-agones-a",
                  "address": "10.244.0.17",
                  "ports": [
                    {"name": "metrics", "port": 9090},
                    {"name": "minecraft", "port": 31565}
                  ],
                  "metadata": {
                    "annotations": {
                      "sh.harold.fulcrum/slot-id": "slot-host-1",
                      "sh.harold.fulcrum/instance-kind": "%s",
                      "sh.harold.fulcrum/principal-id": "principal-paper-agones-1"
                    }
                  }
                }
                """.formatted(instanceKind);
    }

    private static final String TEST_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIC2zCCAcOgAwIBAgIUQALph+8CMT9a0yOOgKm2IvI1+nUwDQYJKoZIhvcNAQEL
            BQAwFjEUMBIGA1UEAwwLYWdvbmVzLXRlc3QwHhcNMjYwNjE3MTkyNzAxWhcNMjYw
            NjE4MTkyNzAxWjAWMRQwEgYDVQQDDAthZ29uZXMtdGVzdDCCASIwDQYJKoZIhvcN
            AQEBBQADggEPADCCAQoCggEBAL9LYtufnUVYGbzv3JOWTkS3+vEaFGiKdHYk4UW4
            Vj4PbZRWRSuU+oYr1J0gyqGfWv7g/7AjLPlJiqyjg1k4aLv/ylmTgWnn8cp8H2ak
            7eqnLxj4+tbKqz03CJBX0xxXc0OxP2ni7PhosRclJSDhxSigKGHrmdi5GKDhgGhA
            gxh7Mmupb9xtYOB9j1hyD1ljyFgGfECE7i52QCIEjJnvvrXTj4NgfKVZPLl4xLDp
            0q8aRqti6eguwsatdVRoS7WQxS+CGlf1Z6/AtQ22Yht5bM5zLMeqpyU+/B8q9Anl
            fF1jVe2bGq6bIYc5H1vLeQkIHO5Lao1ttKYQYNLdOAQVMdECAwEAAaMhMB8wHQYD
            VR0OBBYEFCGcXKs1HiatSvkeEC5+ruXdvGe+MA0GCSqGSIb3DQEBCwUAA4IBAQBU
            GdOaghliXNZ8nQ5Dz+MVmAIBKtLQ1nCNkkkd15EqGUVkjs47cO1OptRXKu1E7aBW
            FuROrnVoKJrnmYSTnThCAuuOobgOnpp7MbS4IWrBuLSvYhDMCAlkcVxYZBxctIcY
            LnBdqrRXspTpIO1PVenpVDFa4im/UOdvs7aIULKn6gEnqElyGrED9uFGIPEfVv3c
            QQA9Ke/pnWe35JuH+RhvFebmc0DuHkR4nrMA7XM1qr91LtWx63blC3QUjsLMtsHD
            dUcdji3iVL1o/aed50JfDWSomiNj+Qgm8TP3l+kFGeQPr7W6v6YnScrWrydGlYLA
            WNm1PqW8rhACjYmAIwVg
            -----END CERTIFICATE-----
            """;

    private static final String TEST_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC/S2Lbn51FWBm8
            79yTlk5Et/rxGhRoinR2JOFFuFY+D22UVkUrlPqGK9SdIMqhn1r+4P+wIyz5SYqs
            o4NZOGi7/8pZk4Fp5/HKfB9mpO3qpy8Y+PrWyqs9NwiQV9McV3NDsT9p4uz4aLEX
            JSUg4cUooChh65nYuRig4YBoQIMYezJrqW/cbWDgfY9Ycg9ZY8hYBnxAhO4udkAi
            BIyZ776104+DYHylWTy5eMSw6dKvGkarYunoLsLGrXVUaEu1kMUvghpX9WevwLUN
            tmIbeWzOcyzHqqclPvwfKvQJ5XxdY1XtmxqumyGHOR9by3kJCBzuS2qNbbSmEGDS
            3TgEFTHRAgMBAAECggEAWeiiOaxe/FEoo7Mu/pUGC2lXggtqbSoqC79Qu/akXj3d
            GHulvhKi3v3py8I2Stl9qM7yjgQyZqZojbS6juwi2b2jCw/3ouG3tJ47GgDJq+DR
            E5EuQFb4PejIVyNYH3mAvN+peOiFzUlTdpfoR/ilNG92G3PjFsgEadUjB5zOoOmx
            RRPZoZz0y0FanPyoRIbiNBswhzOcN8NkzhYfIKdFTlkm8xlYLHeUfLyjhswnJlSw
            RjKHFGfcGGsSvaTakOPWDWYbL8sfLngW/gH8RHkzrg4Bdyp7wxRDf5zXBLMMhnkQ
            GYSz9dkgY+qU4478ogUDAgRDxThzVCnNCaRthhsuWwKBgQD1oPZ7rtLMnnG92X+d
            re7ViOv2BqGMSt2Rp2XcSFb/eWrfDnNJHmJ+0FyNuwdYPyEAFTLeQygUmWIWfyOg
            WCDiO7hWvu4ad0zZFbhomG1HEcZh1t58owzKy6131QfRRUqJ5umgskLxlBm9SklE
            bJSgOHpF5FZ25GtRVg3cCCHdPwKBgQDHXx26U1KYFE0V+L4h5AuWfHbEY6ZgRPrT
            ZHvuKKDc8VTtaFqNGci6cTf4pjFqrgquqYuxYReltbjzKG5+KdwWja1+UWfiVxJ+
            /QqNFBVM+8/sWGYdnzBWs6iDrYMdIz7PmnXoUXLThtawMeXNmJf2xhqyi9etnhj0
            ts32MMtc7wKBgQCv9F19Xk/tencaO8saRjW3y7zUYg2ptRuhslvagAuqOO0g2nYl
            Y9nE5DfY46iwQ5C9QXJOG6eDkhjc6ri3rUnpJkS4B1ADr4BiZhfS/ZYSeh41ijmY
            6ShJwbwDApz2AYAS51Jm5ivkaGZD3go8NNgHKk4U8SwrQRfLjSyieUTg+wKBgQCA
            B97pVrTFoNPX9kLzNKUUYJ1MhMnFLMb+lZrYWBLlj70AMHFmB1bWE/rjnKZDYbzO
            aWah5D3xVn+M9zvtnSgO+7CcW96ghVYFYq4x5uG+7D6cAjCheSbrprfix7xZK9cc
            Lo7lP9jDaeXYhFKU8xczjAh8/Dzm644PKI2fObp+1QKBgDvzX1io5J4YRV9TbfDw
            nDYfHCjlfCALYkGwXT14q9IuuwrAJE3u7vFORnVTZbiQhBd5ZIecC07v88wOKB7+
            fyXFrTb3YMhYEihqXOQpOsZ5P8Nin3SfaNiocNOlcnHLMdRixtmwkdrMD+P8f+yo
            6MJdaBB50ppN4CCN5rgg8gtC
            -----END PRIVATE KEY-----
            """;

    private static final class AllocatorFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();
        private final AtomicReference<String> requestBody = new AtomicReference<>();

        private AllocatorFixture(HttpServer server) {
            this.server = server;
        }

        static AllocatorFixture responding(int statusCode, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            AllocatorFixture fixture = new AllocatorFixture(server);
            server.createContext("/gameserverallocation", exchange -> fixture.handle(exchange, statusCode, responseBody));
            server.start();
            return fixture;
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        String method() {
            return method.get();
        }

        String contentType() {
            return contentType.get();
        }

        String requestBody() {
            return requestBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
