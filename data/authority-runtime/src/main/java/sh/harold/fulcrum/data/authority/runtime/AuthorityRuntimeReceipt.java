package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;

import java.util.Objects;

public record AuthorityRuntimeReceipt(
        AuthorityOffset committedOffset,
        AggregateId aggregateId,
        AuthorityDecisionStatus status,
        Revision revision,
        boolean replayed) {
    public AuthorityRuntimeReceipt {
        committedOffset = Objects.requireNonNull(committedOffset, "committedOffset");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        status = Objects.requireNonNull(status, "status");
        revision = Objects.requireNonNull(revision, "revision");
    }
}
