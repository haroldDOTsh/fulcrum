package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record GuildRosterSnapshot(
        GuildId guildId,
        SubjectId ownerSubjectId,
        List<SubjectId> memberSubjectIds,
        String displayName,
        PrincipalId createdBy,
        Instant createdAt) {
    public GuildRosterSnapshot {
        guildId = Objects.requireNonNull(guildId, "guildId");
        ownerSubjectId = Objects.requireNonNull(ownerSubjectId, "ownerSubjectId");
        displayName = requireNonBlank(displayName, "displayName");
        createdBy = Objects.requireNonNull(createdBy, "createdBy");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        LinkedHashSet<SubjectId> members = new LinkedHashSet<>();
        members.add(ownerSubjectId);
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
        return "guildId=%s\nownerSubjectId=%s\nmemberSubjectIds=%s\nmemberCount=%d\ndisplayName=%s\ncreatedBy=%s\ncreatedAt=%s\nrevision=%d"
                .formatted(
                        guildId.value(),
                        ownerSubjectId.value(),
                        memberWireValue(),
                        memberSubjectIds.size(),
                        displayName,
                        createdBy.value(),
                        createdAt,
                        revision.value());
    }

    public String memberWireValue() {
        return memberSubjectIds.stream()
                .map(subject -> subject.value().toString())
                .collect(Collectors.joining(","));
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
