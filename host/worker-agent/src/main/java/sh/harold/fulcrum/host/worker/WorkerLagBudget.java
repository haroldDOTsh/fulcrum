package sh.harold.fulcrum.host.worker;

import java.time.Duration;
import java.util.Objects;

public record WorkerLagBudget(
        WorkerJobKind jobKind,
        Duration maxLag) {
    public WorkerLagBudget {
        jobKind = Objects.requireNonNull(jobKind, "jobKind");
        maxLag = Objects.requireNonNull(maxLag, "maxLag");
        if (maxLag.isNegative() || maxLag.isZero()) {
            throw new IllegalArgumentException("maxLag must be positive");
        }
    }
}
