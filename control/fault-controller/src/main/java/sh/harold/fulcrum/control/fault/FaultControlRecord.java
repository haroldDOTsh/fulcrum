package sh.harold.fulcrum.control.fault;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record FaultControlRecord(
        Revision revision,
        long fencingEpoch,
        Optional<FaultRecord> faultRecord) {
    public FaultControlRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        faultRecord = faultRecord == null ? Optional.empty() : faultRecord;
    }

    public static FaultControlRecord empty(long fencingEpoch) {
        return new FaultControlRecord(new Revision(0), fencingEpoch, Optional.empty());
    }

    public FaultControlRecord withRecord(Revision nextRevision, FaultRecord record) {
        return new FaultControlRecord(nextRevision, fencingEpoch, Optional.of(record));
    }
}
