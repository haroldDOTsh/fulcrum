package sh.harold.fulcrum.adapters.agones.allocator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

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

    @Test
    void tlsHttpClientLoadsCaPemFileWithoutClientCertificate() throws Exception {
        Path ca = tempDir.resolve("tls-ca.crt");
        Files.writeString(ca, TEST_CERTIFICATE, StandardCharsets.US_ASCII);

        HttpClient client = AgonesAllocatorRestClient.tlsHttpClient(ca);

        assertNotNull(client);
    }

    @Test
    void tlsClientCanDisableHostnameVerificationForIdentitylessAllocatorCerts() throws Exception {
        Path ca = tempDir.resolve("tls-ca.crt");
        Files.writeString(ca, TEST_CERTIFICATE, StandardCharsets.US_ASCII);

        try (AllocatorFixture fixture = AllocatorFixture.respondingHttps(200, allocationResponse("paper"))) {
            AgonesAllocatorRestClient strictClient =
                    AgonesAllocatorRestClient.tls(fixture.uri(), "default", ca);

            IllegalStateException strictFailure =
                    assertThrows(IllegalStateException.class, () -> strictClient.allocate(allocationRequest()));

            assertTrue(strictFailure.getMessage().contains("Agones allocation request failed"));

            AgonesAllocatorRestClient relaxedClient =
                    AgonesAllocatorRestClient.tls(fixture.uri(), "default", ca, true);

            HostAllocationClaim claim = relaxedClient.allocate(allocationRequest());

            assertEquals("slot-host-1", claim.slotId().value());
        }

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
            MIIC8DCCAdigAwIBAgIUEJyeEEOuQmog+7q01+Pokl5bMW8wDQYJKoZIhvcNAQEL
            BQAwFjEUMBIGA1UEAwwLYWdvbmVzLXRlc3QwHhcNMjYwNjIwMDkwMzIxWhcNMzYw
            NjE3MDkwMzIxWjAWMRQwEgYDVQQDDAthZ29uZXMtdGVzdDCCASIwDQYJKoZIhvcN
            AQEBBQADggEPADCCAQoCggEBAKJyo3LmL5m7oB0o6ekEMG23W6jYrbnL1CvVnXF9
            mFS9ZdjabBDY+9WZGgHxEHTZuZcop53CXk6I3elbG0Bj1trtUel6fuiYnmwukWPi
            yRUhHzylWcODbo9v4SVdnSx+/5rEfW5pvdKfUP/cHQLTctw1pIsEMr4PYIHBLItG
            l3ZNuv4HtKUn38x46OmPUYLo8gDYHhxjtHUqeGTuG0UbswVlBSSel99LvyLCPe2n
            aN+WXSE270uQoWLEY3jc/kN7BrKbVkKMQ3J5LXeCGOVWsSDuvj9x4xsjo4K2VOQw
            fTKVEYyN+mL2rCCHNIpWRCAUmSYhtiN0iTh9R8xQm0HHicECAwEAAaM2MDQwEwYD
            VR0lBAwwCgYIKwYBBQUHAwEwHQYDVR0OBBYEFGMQRBifIyPQqImXx5J+IY1QYEy4
            MA0GCSqGSIb3DQEBCwUAA4IBAQAnGnwvkVatYTQ2aJx+z1njk07eKNgEdu8F5Ec7
            OQEwjjuhQLiiPGVtbibJ2k8GJuS1NGXyoCt3KiCvwZOHQ/vY1DqZHz5GYNxwscvH
            pvq8dpWWhgtdKIxdTx0SPVr8ElELQ03fQDkfgVNSEyAHOGnvB3XBCNi84+4X6NLh
            1xYJbfCxfn45PNHEs8LGqkYCiOx3PvhoS0r9foRFatja6HYoD3KqaRk2xZ5Y6Z6w
            EmcyyJMzvsjuzeRKEHjHqwlyRLiAyVx43iZ2opqa8w8pY0AqKfqqzJoX/ZsrwaYY
            /bPW+zcAnAYO+K5v5V51CiH7q7H7aCBmGqWbzJZEvk/7ZVVc
            -----END CERTIFICATE-----
            """;

    private static final String TEST_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCicqNy5i+Zu6Ad
            KOnpBDBtt1uo2K25y9Qr1Z1xfZhUvWXY2mwQ2PvVmRoB8RB02bmXKKedwl5OiN3p
            WxtAY9ba7VHpen7omJ5sLpFj4skVIR88pVnDg26Pb+ElXZ0sfv+axH1uab3Sn1D/
            3B0C03LcNaSLBDK+D2CBwSyLRpd2Tbr+B7SlJ9/MeOjpj1GC6PIA2B4cY7R1Knhk
            7htFG7MFZQUknpffS78iwj3tp2jfll0hNu9LkKFixGN43P5Dewaym1ZCjENyeS13
            ghjlVrEg7r4/ceMbI6OCtlTkMH0ylRGMjfpi9qwghzSKVkQgFJkmIbYjdIk4fUfM
            UJtBx4nBAgMBAAECggEABTFi9jZTiTeNO8FmENPYdHlrDa+3hfv1EV2nxcv9BaB0
            VAVPdQ5qpn5ZbXx2STX4j9N14D4pY5tOdLo72cgXQzJY7vzCd88BKXYnoajLQsin
            RfoHL4/RBfbOnLIsZxK8OwdS9yecMNJ09Wjk6IU348A3PVZqBNda/2rccKtWBisS
            27EBYfIx0XBwL06c366rX9BVfQ/WSmkL/RJvewQZAXCLgvnUn9Vp56z5VtsVvOZ7
            dV7gR7PPOQUX5Ao1BawFT0wi+u0Ptu6dPtmTSh1kPAH7T5CTQXZ3PcXnzCRNJ6ap
            C5b33Q1SPZGhdK+ZNvUNBjwA1aX8kz3N3NgU4AhgUQKBgQDekX8LURqv6R+0Hb+4
            JEHPM16xDZ+izDuIe5k5oQ+lOrsakKGusycUFO1NzZFFrWJ/dlFIANuvUeRrNMGP
            O7QM6a0W6HrNA9Yxaxdm8LD3S+/aSMFsg3ZtTkRJ6iPYEm7cWZUywb9Nz9+oj4Mo
            KQY0zQ/z48dYNLT/ayg8b+5MXQKBgQC62U3/mrt3ih1BC/0Ip2pGjANZF2bpq/Ju
            nSbeSU6l3BIIc88Dm5TA6ogyf5d4XknROLm0qaRWCviKwy3BZ7IVHiswb7EYGQ86
            bwz0OTz2JdzhkzxmNq/vqzOnQmCOpbtS3LMx8AKLS+eTB0gvU4aigrwXVupiWp39
            hDwUBaD8tQKBgQC0vgkiwpFei32ggowf2OnMfxYFyF98EEjEVEMhZqdS8ffh4dQ7
            D+fLShdQGIFByUT057uoMnI01NcfLG+Hht93oQhcUxzugpAd/664fPvpR7SXWoAh
            RD0XFPkl4UuMe6Ols+YSmv5lDUu/EhRbt7z/ggvTboWDHwJhbb72HZuyTQKBgCYB
            IR5GSK8txnl+iL8D3lfvDpdGbUZGFQ9uo4M/AeI2euyBMbAYKw96JK2wygxPkVAe
            65bVknl1zcvbmyjlgJFPC5XUgf7WygQmpknegonGdcDkA7r+kJZ9CgqRM7aP+yQF
            g+U6XiobDEUZjBMkOBRB5yQQJ6hNqijwho/D/VSpAoGAEDiqlAKZjIs0p8j1Yk/Q
            zK/P95q8eIJClURxuywh2nAA2PQZGb3wyvSXJmGbnZ56U/28Dk7bFNH8sLdDG3h8
            U8lYDSwwX1I89T+N5T+VPBy5Yc5+U6gXIX7iSXocU1LZePkLNyTq26feYe7etfEp
            9O09vKp1qCh5w8pqPt63xHw=
            -----END PRIVATE KEY-----
            """;

    private static final class AllocatorFixture implements AutoCloseable {
        private final HttpServer server;
        private final String scheme;
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();
        private final AtomicReference<String> requestBody = new AtomicReference<>();

        private AllocatorFixture(HttpServer server, String scheme) {
            this.server = server;
            this.scheme = scheme;
        }

        static AllocatorFixture responding(int statusCode, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            AllocatorFixture fixture = new AllocatorFixture(server, "http");
            server.createContext("/gameserverallocation", exchange -> fixture.handle(exchange, statusCode, responseBody));
            server.start();
            return fixture;
        }

        static AllocatorFixture respondingHttps(int statusCode, String responseBody) throws Exception {
            HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
            AllocatorFixture fixture = new AllocatorFixture(server, "https");
            server.createContext("/gameserverallocation", exchange -> fixture.handle(exchange, statusCode, responseBody));
            server.start();
            return fixture;
        }

        URI uri() {
            return URI.create(scheme + "://127.0.0.1:" + server.getAddress().getPort());
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

        private static SSLContext serverSslContext() throws Exception {
            Certificate certificate;
            try (ByteArrayInputStream input =
                         new ByteArrayInputStream(TEST_CERTIFICATE.getBytes(StandardCharsets.US_ASCII))) {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes()));
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("allocator-test", privateKey, new char[0], new Certificate[]{certificate});
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, new char[0]);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), null, null);
            return context;
        }

        private static byte[] privateKeyBytes() {
            String base64 = TEST_PRIVATE_KEY
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(base64);
        }
    }
}
