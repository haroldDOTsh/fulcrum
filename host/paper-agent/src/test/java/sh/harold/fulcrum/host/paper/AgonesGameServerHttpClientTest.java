package sh.harold.fulcrum.host.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgonesGameServerHttpClientTest {
    @Test
    void sendsLifecycleCallsToSdkServer() throws IOException {
        try (SdkFixture fixture = SdkFixture.start()) {
            AgonesGameServerHttpClient client = new AgonesGameServerHttpClient(fixture.uri());

            client.ready();
            client.health();
            client.allocate();
            client.reserve(Duration.ofSeconds(30));
            client.shutdown();

            assertEquals(List.of(
                    "POST /ready {}",
                    "POST /health {}",
                    "POST /allocate {}",
                    "POST /reserve {\"seconds\":30}",
                    "POST /shutdown {}"), fixture.requests());
        }
    }

    @Test
    void readsGameServerSnapshot() throws IOException {
        try (SdkFixture fixture = SdkFixture.start()) {
            AgonesGameServerSnapshot snapshot = new AgonesGameServerHttpClient(fixture.uri()).gameServer();

            assertEquals("lobby-gameserver-1", snapshot.name());
            assertEquals("fulcrum", snapshot.namespace());
            assertEquals("Allocated", snapshot.state());
            assertEquals("session-lobby-allocated", snapshot.annotation(AgonesGameServerSnapshot.SESSION_ID_ANNOTATION).orElseThrow());
            assertEquals("slot-lobby-allocated", snapshot.annotation(AgonesGameServerSnapshot.SLOT_ID_ANNOTATION).orElseThrow());
            assertTrue(snapshot.rawJson().contains("lobby-gameserver-1"));
        }
    }

    @Test
    void rejectsSdkFailuresAndInvalidReserveDuration() throws IOException {
        try (SdkFixture fixture = SdkFixture.start()) {
            AgonesGameServerHttpClient client = new AgonesGameServerHttpClient(fixture.uri());

            assertThrows(IllegalArgumentException.class, () -> client.reserve(Duration.ZERO));
            assertThrows(IllegalStateException.class, () -> client.reserve(Duration.ofSeconds(13)));
        }
    }

    private static final class SdkFixture implements AutoCloseable {
        private final HttpServer server;
        private final List<String> requests = new ArrayList<>();

        private SdkFixture(HttpServer server) {
            this.server = server;
        }

        static SdkFixture start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SdkFixture fixture = new SdkFixture(server);
            server.createContext("/", fixture::handle);
            server.start();
            return fixture;
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + path + " " + body);
            if (path.equals("/reserve") && body.contains("13")) {
                respond(exchange, 500, "{}");
                return;
            }
            if (path.equals("/gameserver")) {
                respond(exchange, 200, """
                        {
                          "objectMeta": {
                            "name": "lobby-gameserver-1",
                            "namespace": "fulcrum",
                            "annotations": {
                              "sh.harold.fulcrum/session-id": "session-lobby-allocated",
                              "sh.harold.fulcrum/slot-id": "slot-lobby-allocated",
                              "sh.harold.fulcrum/resolved-manifest-id": "manifest-lobby"
                            }
                          },
                          "status": {
                            "state": "Allocated"
                          }
                        }
                        """);
                return;
            }
            respond(exchange, 200, "{}");
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        }
    }
}
