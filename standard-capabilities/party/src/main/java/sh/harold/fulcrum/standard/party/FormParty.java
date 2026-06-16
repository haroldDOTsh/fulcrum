package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record FormParty(
        PartyId partyId,
        SubjectId leaderSubjectId,
        List<SubjectId> memberSubjectIds,
        Instant formedAt,
        long expectedRevision) implements CommandPayload {
    public FormParty {
        partyId = Objects.requireNonNull(partyId, "partyId");
        leaderSubjectId = Objects.requireNonNull(leaderSubjectId, "leaderSubjectId");
        formedAt = Objects.requireNonNull(formedAt, "formedAt");
        LinkedHashSet<SubjectId> members = new LinkedHashSet<>();
        members.add(leaderSubjectId);
        Objects.requireNonNull(memberSubjectIds, "memberSubjectIds").forEach(member -> members.add(Objects.requireNonNull(member, "member")));
        memberSubjectIds = List.copyOf(members);
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }
}
