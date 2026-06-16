package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record PartyRosterSnapshot(
        PartyId partyId,
        SubjectId leaderSubjectId,
        List<SubjectId> memberSubjectIds,
        PrincipalId formedBy,
        Instant formedAt) {
    public PartyRosterSnapshot {
        partyId = Objects.requireNonNull(partyId, "partyId");
        leaderSubjectId = Objects.requireNonNull(leaderSubjectId, "leaderSubjectId");
        formedBy = Objects.requireNonNull(formedBy, "formedBy");
        formedAt = Objects.requireNonNull(formedAt, "formedAt");
        LinkedHashSet<SubjectId> members = new LinkedHashSet<>();
        members.add(leaderSubjectId);
        Objects.requireNonNull(memberSubjectIds, "memberSubjectIds").forEach(member -> members.add(Objects.requireNonNull(member, "member")));
        memberSubjectIds = members.stream()
                .sorted(Comparator.comparing(subject -> subject.value().toString()))
                .toList();
    }

    public boolean contains(SubjectId subjectId) {
        return memberSubjectIds.contains(Objects.requireNonNull(subjectId, "subjectId"));
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return "partyId=%s\nleaderSubjectId=%s\nmemberSubjectIds=%s\nmemberCount=%d\nformedBy=%s\nformedAt=%s\nrevision=%d"
                .formatted(
                        partyId.value(),
                        leaderSubjectId.value(),
                        memberWireValue(),
                        memberSubjectIds.size(),
                        formedBy.value(),
                        formedAt,
                        revision.value());
    }

    public String memberWireValue() {
        return memberSubjectIds.stream()
                .map(subject -> subject.value().toString())
                .collect(Collectors.joining(","));
    }
}
