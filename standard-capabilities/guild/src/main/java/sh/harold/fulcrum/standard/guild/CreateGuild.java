package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record CreateGuild(
        GuildId guildId,
        SubjectId ownerSubjectId,
        List<SubjectId> memberSubjectIds,
        String displayName,
        Instant createdAt,
        long expectedRevision) implements CommandPayload {
    public CreateGuild {
        guildId = Objects.requireNonNull(guildId, "guildId");
        ownerSubjectId = Objects.requireNonNull(ownerSubjectId, "ownerSubjectId");
        displayName = requireNonBlank(displayName, "displayName");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        LinkedHashSet<SubjectId> members = new LinkedHashSet<>();
        members.add(ownerSubjectId);
        Objects.requireNonNull(memberSubjectIds, "memberSubjectIds").forEach(member -> members.add(Objects.requireNonNull(member, "member")));
        memberSubjectIds = List.copyOf(members);
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
