package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record CommandReadConsistencyReceipt<T>(
        CommandReadConsistency consistency,
        Revision authorityRevision,
        Optional<ProjectionSnapshot<T>> postWriteProjection,
        Optional<CommandReadConsistencyViolation> violation,
        boolean readYourWritesSatisfied) {
    public CommandReadConsistencyReceipt {
        consistency = Objects.requireNonNull(consistency, "consistency");
        authorityRevision = Objects.requireNonNull(authorityRevision, "authorityRevision");
        postWriteProjection = postWriteProjection == null ? Optional.empty() : postWriteProjection;
        violation = violation == null ? Optional.empty() : violation;
        if (violation.isPresent() && readYourWritesSatisfied) {
            throw new IllegalArgumentException("invalid consistency receipt cannot satisfy read-your-writes");
        }
    }

    public boolean valid() {
        return violation.isEmpty();
    }
}
