package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record GuildRosterProjection(
        Map<GuildId, GuildRosterProjectionRow> guilds,
        Map<SubjectId, GuildId> guildBySubject) {
    public GuildRosterProjection {
        guilds = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(guilds, "guilds")));
        guildBySubject = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(guildBySubject, "guildBySubject")));
    }

    public static GuildRosterProjection empty() {
        return new GuildRosterProjection(Map.of(), Map.of());
    }

    public static GuildRosterProjection rebuild(List<GuildCreated> events) {
        Objects.requireNonNull(events, "events");
        LinkedHashMap<GuildId, GuildRosterProjectionRow> guilds = new LinkedHashMap<>();
        LinkedHashMap<SubjectId, GuildId> guildBySubject = new LinkedHashMap<>();
        for (GuildCreated event : events) {
            GuildRosterProjectionRow row = new GuildRosterProjectionRow(event.snapshot(), event.revision());
            GuildRosterProjectionRow current = guilds.get(row.guildId());
            if (current != null && row.revision().value() <= current.revision().value()) {
                throw new IllegalArgumentException("guild projection replay requires increasing revisions per Guild");
            }
            for (SubjectId subjectId : row.snapshot().memberSubjectIds()) {
                GuildId existingGuild = guildBySubject.get(subjectId);
                if (existingGuild != null && !existingGuild.equals(row.guildId())) {
                    throw new IllegalArgumentException("Subject cannot be indexed into more than one active Guild");
                }
                guildBySubject.put(subjectId, row.guildId());
            }
            guilds.put(row.guildId(), row);
        }
        return new GuildRosterProjection(guilds, guildBySubject);
    }

    public Optional<GuildRosterProjectionRow> row(GuildId guildId) {
        return Optional.ofNullable(guilds.get(Objects.requireNonNull(guildId, "guildId")));
    }

    public Optional<GuildId> guildFor(SubjectId subjectId) {
        return Optional.ofNullable(guildBySubject.get(Objects.requireNonNull(subjectId, "subjectId")));
    }

    public List<SubjectId> membersFor(SubjectId subjectId) {
        return guildFor(subjectId)
                .flatMap(this::row)
                .map(row -> row.snapshot().memberSubjectIds())
                .orElse(List.of());
    }
}
