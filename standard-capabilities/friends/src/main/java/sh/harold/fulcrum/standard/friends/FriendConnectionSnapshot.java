package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record FriendConnectionSnapshot(
        FriendConnectionId connectionId,
        SubjectId subjectOneId,
        SubjectId subjectTwoId,
        SubjectId requesterSubjectId,
        SubjectId accepterSubjectId,
        PrincipalId acceptedBy,
        Instant acceptedAt) {
    public FriendConnectionSnapshot {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        subjectOneId = Objects.requireNonNull(subjectOneId, "subjectOneId");
        subjectTwoId = Objects.requireNonNull(subjectTwoId, "subjectTwoId");
        requesterSubjectId = Objects.requireNonNull(requesterSubjectId, "requesterSubjectId");
        accepterSubjectId = Objects.requireNonNull(accepterSubjectId, "accepterSubjectId");
        acceptedBy = Objects.requireNonNull(acceptedBy, "acceptedBy");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (subjectOneId.equals(subjectTwoId) || requesterSubjectId.equals(accepterSubjectId)) {
            throw new IllegalArgumentException("friend connection requires two distinct Subjects");
        }
        if (!connectionId.equals(FriendConnectionId.from(subjectOneId, subjectTwoId))) {
            throw new IllegalArgumentException("friend connection id must match canonical Subject pair");
        }
        if (!List.of(subjectOneId, subjectTwoId).containsAll(List.of(requesterSubjectId, accepterSubjectId))) {
            throw new IllegalArgumentException("requester and accepter must match connection Subjects");
        }
    }

    public static FriendConnectionSnapshot accepted(
            SubjectId requesterSubjectId,
            SubjectId accepterSubjectId,
            PrincipalId acceptedBy,
            Instant acceptedAt) {
        List<SubjectId> ordered = List.of(requesterSubjectId, accepterSubjectId).stream()
                .sorted(Comparator.comparing(subject -> subject.value().toString()))
                .toList();
        return new FriendConnectionSnapshot(
                FriendConnectionId.from(requesterSubjectId, accepterSubjectId),
                ordered.get(0),
                ordered.get(1),
                requesterSubjectId,
                accepterSubjectId,
                acceptedBy,
                acceptedAt);
    }

    public SubjectId otherSubject(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        if (subjectOneId.equals(subjectId)) {
            return subjectTwoId;
        }
        if (subjectTwoId.equals(subjectId)) {
            return subjectOneId;
        }
        throw new IllegalArgumentException("Subject is not part of friend connection");
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return "connectionId=%s\nsubjectOneId=%s\nsubjectTwoId=%s\nrequesterSubjectId=%s\naccepterSubjectId=%s\nacceptedBy=%s\nacceptedAt=%s\nrevision=%d"
                .formatted(
                        connectionId.value(),
                        subjectOneId.value(),
                        subjectTwoId.value(),
                        requesterSubjectId.value(),
                        accepterSubjectId.value(),
                        acceptedBy.value(),
                        acceptedAt,
                        revision.value());
    }
}
