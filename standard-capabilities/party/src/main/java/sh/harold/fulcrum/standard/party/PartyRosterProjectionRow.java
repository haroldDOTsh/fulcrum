package sh.harold.fulcrum.standard.party;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record PartyRosterProjectionRow(PartyRosterSnapshot snapshot, Revision revision) {
    public PartyRosterProjectionRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public PartyId partyId() {
        return snapshot.partyId();
    }
}
