package sh.harold.fulcrum.standard.punishment;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record PunishmentIssued(ActivePunishmentSnapshot snapshot, Revision revision) {
    public PunishmentIssued {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return "punishment-issued\n" + snapshot.wireValue(revision.value());
    }
}
