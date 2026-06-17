package sh.harold.fulcrum.distribution.launcher;

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
import sh.harold.fulcrum.host.paper.PaperObservationSink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PaperObservationBridgeServerTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void acceptsPluginObservationAndPublishesThroughConfiguredSink() throws Exception {
        RecordingObservationSink sink = new RecordingObservationSink();
        try (PaperObservationBridgeServer bridge = new PaperObservationBridgeServer(
                URI.create("http://127.0.0.1:0/observations"),
                sink)) {
            bridge.start();
            HostObservation observation = observation();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(bridge.uri())
                            .header("Content-Type", "text/plain; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    HostObservationWireCodec.encode(observation),
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(202, response.statusCode());
            awaitPublished(sink);
            assertNull(bridge.failure());
            assertEquals(observation.instanceId(), sink.observations().getFirst().instanceId());
            assertEquals(observation.observationType(), sink.observations().getFirst().observationType());
        }
    }

    private static void awaitPublished(RecordingObservationSink sink) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!sink.observations().isEmpty()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Paper observation bridge did not publish observation");
    }

    private static HostObservation observation() {
        HostInstanceIdentity identity = new HostInstanceIdentity(
                new InstanceId("instance-paper-bridge"),
                HostInstanceKinds.PAPER,
                new PoolId("pool-paper-bridge"),
                new MachineRef("machine-paper-bridge"),
                new PrincipalId("principal-paper-bridge"));
        TraceEnvelope trace = new TraceEnvelope(
                "trace-paper-bridge",
                "span-paper-bridge",
                Optional.empty(),
                NOW,
                "paper-agent",
                identity.instanceId());
        return HostObservationFactory.readiness(new HostReadinessReport(
                identity,
                new ResolvedManifestId("manifest-paper-bridge"),
                trace,
                NOW));
    }

    private static final class RecordingObservationSink implements PaperObservationSink {
        private final List<HostObservation> observations = new ArrayList<>();

        @Override
        public synchronized void publish(HostObservation observation) {
            observations.add(observation);
        }

        private synchronized List<HostObservation> observations() {
            return List.copyOf(observations);
        }
    }
}
