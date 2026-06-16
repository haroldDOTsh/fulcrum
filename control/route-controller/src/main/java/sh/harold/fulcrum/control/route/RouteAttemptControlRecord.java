package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record RouteAttemptControlRecord(
        Revision revision,
        long fencingEpoch,
        Optional<RouteAttemptSnapshot> snapshot) {
    public RouteAttemptControlRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        snapshot = snapshot == null ? Optional.empty() : snapshot;
    }

    public static RouteAttemptControlRecord empty(long fencingEpoch) {
        return new RouteAttemptControlRecord(new Revision(0), fencingEpoch, Optional.empty());
    }

    public RouteAttemptControlRecord withSnapshot(Revision nextRevision, RouteAttemptSnapshot nextSnapshot) {
        return new RouteAttemptControlRecord(nextRevision, fencingEpoch, Optional.of(nextSnapshot));
    }
}
