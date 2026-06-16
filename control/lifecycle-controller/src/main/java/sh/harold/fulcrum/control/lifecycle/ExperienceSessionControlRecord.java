package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record ExperienceSessionControlRecord(
        Revision revision,
        long fencingEpoch,
        Optional<ExperienceSessionRecord> sessionRecord) {
    public ExperienceSessionControlRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        sessionRecord = sessionRecord == null ? Optional.empty() : sessionRecord;
    }

    public static ExperienceSessionControlRecord empty(long fencingEpoch) {
        return new ExperienceSessionControlRecord(new Revision(0), fencingEpoch, Optional.empty());
    }

    public ExperienceSessionControlRecord withRecord(Revision nextRevision, ExperienceSessionRecord nextRecord) {
        return new ExperienceSessionControlRecord(nextRevision, fencingEpoch, Optional.of(nextRecord));
    }
}
