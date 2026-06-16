package sh.harold.fulcrum.adapters.agones.fake;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record WarmAllocationLatencyReport(
        int sampleCount,
        Duration p50,
        Duration p95,
        Duration p99) {
    public WarmAllocationLatencyReport {
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
        p50 = Objects.requireNonNull(p50, "p50");
        p95 = Objects.requireNonNull(p95, "p95");
        p99 = Objects.requireNonNull(p99, "p99");
    }

    public static WarmAllocationLatencyReport fromSamples(List<Duration> samples) {
        List<Duration> sorted = Objects.requireNonNull(samples, "samples").stream()
                .map(duration -> Objects.requireNonNull(duration, "duration"))
                .peek(duration -> {
                    if (duration.isNegative()) {
                        throw new IllegalArgumentException("duration samples must not be negative");
                    }
                })
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        return new WarmAllocationLatencyReport(
                sorted.size(),
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99));
    }

    private static Duration percentile(List<Duration> sortedSamples, int percentile) {
        int nearestRank = (int) Math.ceil((percentile / 100.0d) * sortedSamples.size());
        return sortedSamples.get(Math.max(0, nearestRank - 1));
    }
}
