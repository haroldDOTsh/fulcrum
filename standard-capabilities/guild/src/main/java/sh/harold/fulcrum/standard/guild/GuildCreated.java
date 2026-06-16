package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record GuildCreated(GuildRosterSnapshot snapshot, Revision revision) {
    public GuildCreated {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return snapshot.wireValue(revision);
    }
}
