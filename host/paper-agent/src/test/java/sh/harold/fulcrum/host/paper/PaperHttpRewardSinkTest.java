package sh.harold.fulcrum.host.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PaperHttpRewardSinkTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void postsEncodedRewardReportToBridgeEndpoint() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (BridgeFixture bridge = BridgeFixture.start(requestBody)) {
            PaperHttpRewardSink sink = new PaperHttpRewardSink(bridge.uri());
            PaperSessionRewardReport report = rewardReport();

            sink.publish(report);

            PaperSessionRewardReport decoded = PaperSessionRewardReportCodec.decode(requestBody.get());
            assertEquals(report, decoded);
        }
    }

    private static PaperSessionRewardReport rewardReport() {
        InstanceId instanceId = new InstanceId("instance-paper-http-reward");
        return new PaperSessionRewardReport(
                instanceId,
                new SessionId("session-paper-http-reward"),
                new RouteId("route-paper-http-reward"),
                new SubjectId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new TraceEnvelope(
                        "trace-paper-http-reward",
                        "span-paper-http-reward",
                        Optional.empty(),
                        NOW,
                        "paper-agent",
                        instanceId),
                NOW);
    }

    private static final class BridgeFixture implements AutoCloseable {
        private final HttpServer server;

        private BridgeFixture(HttpServer server) {
            this.server = server;
        }

        static BridgeFixture start(AtomicReference<String> requestBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rewards", exchange -> handle(exchange, requestBody));
            server.start();
            return new BridgeFixture(server);
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rewards");
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void handle(HttpExchange exchange, AtomicReference<String> requestBody) throws IOException {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "accepted\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        }
    }
}
