package sh.harold.fulcrum.adapters.agones.fake;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WarmAllocationLatencyReportTest {
    @Test
    void reportsP50P95AndP99WithNearestRankPercentiles() {
        List<Duration> samples = LongStream.rangeClosed(1, 100)
                .mapToObj(Duration::ofMillis)
                .toList();

        WarmAllocationLatencyReport report = WarmAllocationLatencyReport.fromSamples(samples);

        assertEquals(100, report.sampleCount());
        assertEquals(Duration.ofMillis(50), report.p50());
        assertEquals(Duration.ofMillis(95), report.p95());
        assertEquals(Duration.ofMillis(99), report.p99());
    }

    @Test
    void sortsSamplesBeforeReportingPercentiles() {
        WarmAllocationLatencyReport report = WarmAllocationLatencyReport.fromSamples(List.of(
                Duration.ofMillis(20),
                Duration.ofMillis(1),
                Duration.ofMillis(5),
                Duration.ofMillis(10)));

        assertEquals(Duration.ofMillis(5), report.p50());
        assertEquals(Duration.ofMillis(20), report.p95());
        assertEquals(Duration.ofMillis(20), report.p99());
    }

    @Test
    void rejectsEmptySamples() {
        assertThrows(IllegalArgumentException.class, () -> WarmAllocationLatencyReport.fromSamples(List.of()));
    }

    @Test
    void rejectsNegativeSamples() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WarmAllocationLatencyReport.fromSamples(List.of(Duration.ofMillis(-1))));
    }
}
