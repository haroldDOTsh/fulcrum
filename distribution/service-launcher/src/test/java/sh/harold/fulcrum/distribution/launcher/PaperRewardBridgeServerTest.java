package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.host.paper.PaperRewardSink;
import sh.harold.fulcrum.host.paper.PaperSessionRewardReport;
import sh.harold.fulcrum.host.paper.PaperSessionRewardReportCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PaperRewardBridgeServerTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void acceptsPluginRewardReportAndPublishesThroughConfiguredSink() throws Exception {
        RecordingRewardSink sink = new RecordingRewardSink();
        try (PaperRewardBridgeServer bridge = new PaperRewardBridgeServer(
                URI.create("http://127.0.0.1:0/rewards"),
                sink)) {
            bridge.start();
            PaperSessionRewardReport report = rewardReport();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(bridge.uri())
                            .header("Content-Type", "text/plain; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    PaperSessionRewardReportCodec.encode(report),
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(202, response.statusCode());
            awaitPublished(sink);
            assertNull(bridge.failure());
            assertEquals(report, sink.reports().getFirst());
        }
    }

    @Test
    void canInjectDuplicateRewardReportDeliveryWithSamePayload() throws Exception {
        RecordingRewardSink sink = new RecordingRewardSink();
        try (PaperRewardBridgeServer bridge = new PaperRewardBridgeServer(
                URI.create("http://127.0.0.1:0/rewards"),
                sink,
                2)) {
            bridge.start();
            PaperSessionRewardReport report = rewardReport();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(bridge.uri())
                            .header("Content-Type", "text/plain; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    PaperSessionRewardReportCodec.encode(report),
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(202, response.statusCode());
            awaitPublished(sink, 2);
            assertNull(bridge.failure());
            assertEquals(List.of(report, report), sink.reports());
        }
    }

    private static void awaitPublished(RecordingRewardSink sink) throws InterruptedException {
        awaitPublished(sink, 1);
    }

    private static void awaitPublished(RecordingRewardSink sink, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (sink.reports().size() >= expectedCount) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Paper reward bridge did not publish report");
    }

    private static PaperSessionRewardReport rewardReport() {
        InstanceId instanceId = new InstanceId("instance-paper-reward-bridge");
        return new PaperSessionRewardReport(
                instanceId,
                new SessionId("session-paper-reward-bridge"),
                new RouteId("route-paper-reward-bridge"),
                new SubjectId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new TraceEnvelope(
                        "trace-paper-reward-bridge",
                        "span-paper-reward-bridge",
                        Optional.empty(),
                        NOW,
                        "paper-agent",
                        instanceId),
                NOW);
    }

    private static final class RecordingRewardSink implements PaperRewardSink {
        private final List<PaperSessionRewardReport> reports = new ArrayList<>();

        @Override
        public synchronized void publish(PaperSessionRewardReport report) {
            reports.add(report);
        }

        private synchronized List<PaperSessionRewardReport> reports() {
            return List.copyOf(reports);
        }
    }
}
