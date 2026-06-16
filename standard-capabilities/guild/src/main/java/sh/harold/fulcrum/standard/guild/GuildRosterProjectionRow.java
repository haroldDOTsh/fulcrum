package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record GuildRosterProjectionRow(GuildRosterSnapshot snapshot, Revision revision) {
    public GuildRosterProjectionRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public GuildId guildId() {
        return snapshot.guildId();
    }
}
