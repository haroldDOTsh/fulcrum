package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record PartyFormed(PartyRosterSnapshot snapshot, Revision revision) {
    public PartyFormed {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return snapshot.wireValue(revision);
    }
}
