package sh.harold.fulcrum.adapters.agones.allocator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgonesAllocatorRestClientTest {
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
            assertTrue(body.contains("\"agones.dev/fleet\":\"pool-paper-small\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/session-id\":\"session-host-1\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/resolved-manifest-id\":\"manifest-paper-1\""));
            assertTrue(body.contains("\"sh.harold.fulcrum/trace-id\":\"trace-host-1\""));

            assertEquals("slot-agones-1", claim.slotId().value());
            assertEquals(request.sessionId(), claim.sessionId());
            assertEquals(request.resolvedManifestId(), claim.resolvedManifestId());
            assertEquals(request.traceEnvelope(), claim.traceEnvelope());
            assertEquals(request.requestedAt(), claim.allocatedAt());
            assertEquals("instance-paper-agones-1", claim.instanceIdentity().instanceId().value());
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
    void missingIdentityMetadataFailsAllocation() throws Exception {
        String responseBody = """
                {
                  "gameServerName": "agones-gameserver-1",
                  "nodeName": "machine-agones-a",
                  "metadata": {
                    "annotations": {
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

            assertTrue(exception.getMessage().contains(AgonesAllocatorJson.INSTANCE_ID_ANNOTATION));
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
                  "metadata": {
                    "annotations": {
                      "sh.harold.fulcrum/instance-id": "instance-paper-agones-1",
                      "sh.harold.fulcrum/slot-id": "slot-agones-1",
                      "sh.harold.fulcrum/instance-kind": "%s",
                      "sh.harold.fulcrum/principal-id": "principal-paper-agones-1"
                    }
                  }
                }
                """.formatted(instanceKind);
    }

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
