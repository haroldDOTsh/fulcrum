package sh.harold.fulcrum.control.queue;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record QueueRosterControlRecord(
        Revision revision,
        long fencingEpoch,
        QueueRosterState state) {
    public QueueRosterControlRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        state = Objects.requireNonNull(state, "state");
    }

    public static QueueRosterControlRecord empty(long fencingEpoch) {
        return new QueueRosterControlRecord(new Revision(0), fencingEpoch, QueueRosterState.empty());
    }

    public QueueRosterControlRecord withState(Revision nextRevision, QueueRosterState nextState) {
        return new QueueRosterControlRecord(nextRevision, fencingEpoch, nextState);
    }
}
