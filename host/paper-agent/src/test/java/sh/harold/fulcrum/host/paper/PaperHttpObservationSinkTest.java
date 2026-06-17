package sh.harold.fulcrum.host.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostObservationWireCodec;
import sh.harold.fulcrum.host.api.HostReadinessReport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PaperHttpObservationSinkTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void postsEncodedObservationToBridgeEndpoint() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (BridgeFixture bridge = BridgeFixture.start(requestBody)) {
            PaperHttpObservationSink sink = new PaperHttpObservationSink(bridge.uri());
            HostObservation observation = observation();

            sink.publish(observation);

            HostObservation decoded = HostObservationWireCodec.decode(requestBody.get());
            assertEquals(observation.instanceId(), decoded.instanceId());
            assertEquals(observation.observationType(), decoded.observationType());
            assertEquals(observation.attributes(), decoded.attributes());
        }
    }

    private static HostObservation observation() {
        HostInstanceIdentity identity = new HostInstanceIdentity(
                new InstanceId("instance-paper-http-sink"),
                HostInstanceKinds.PAPER,
                new PoolId("pool-paper-http-sink"),
                new MachineRef("machine-http-sink"),
                new PrincipalId("principal-paper-http-sink"));
        TraceEnvelope trace = new TraceEnvelope(
                "trace-paper-http-sink",
                "span-paper-http-sink",
                Optional.empty(),
                NOW,
                "paper-agent",
                identity.instanceId());
        return HostObservationFactory.readiness(new HostReadinessReport(
                identity,
                new ResolvedManifestId("manifest-paper-http-sink"),
                trace,
                NOW));
    }

    private static final class BridgeFixture implements AutoCloseable {
        private final HttpServer server;

        private BridgeFixture(HttpServer server) {
            this.server = server;
        }

        static BridgeFixture start(AtomicReference<String> requestBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/observations", exchange -> handle(exchange, requestBody));
            server.start();
            return new BridgeFixture(server);
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/observations");
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
