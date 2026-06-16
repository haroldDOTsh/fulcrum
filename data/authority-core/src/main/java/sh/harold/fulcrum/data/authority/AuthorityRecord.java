package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record AuthorityRecord<S>(
        Revision revision,
        long fencingEpoch,
        S state) {
    public AuthorityRecord {
        revision = Objects.requireNonNull(revision, "revision");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
    }
}
