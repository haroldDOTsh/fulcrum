package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Objects;

public record AcceptFriendInvite(
        SubjectId requesterSubjectId,
        SubjectId accepterSubjectId,
        Instant acceptedAt,
        long expectedRevision) implements CommandPayload {
    public AcceptFriendInvite {
        requesterSubjectId = Objects.requireNonNull(requesterSubjectId, "requesterSubjectId");
        accepterSubjectId = Objects.requireNonNull(accepterSubjectId, "accepterSubjectId");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (requesterSubjectId.equals(accepterSubjectId)) {
            throw new IllegalArgumentException("friend invite requires two distinct Subjects");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    public FriendConnectionId connectionId() {
        return FriendConnectionId.from(requesterSubjectId, accepterSubjectId);
    }
}
