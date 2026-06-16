package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record LifecycleTraceControlRecord(
        Revision revision,
        long fencingEpoch,
        LifecycleTraceRecord traceRecord) {
    public LifecycleTraceControlRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        traceRecord = Objects.requireNonNull(traceRecord, "traceRecord");
    }

    public static LifecycleTraceControlRecord empty(long fencingEpoch, LifecycleTraceId traceId) {
        return new LifecycleTraceControlRecord(new Revision(0), fencingEpoch, LifecycleTraceRecord.empty(traceId));
    }

    public LifecycleTraceControlRecord withRecord(Revision nextRevision, LifecycleTraceRecord nextRecord) {
        return new LifecycleTraceControlRecord(nextRevision, fencingEpoch, nextRecord);
    }
}
