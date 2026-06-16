package sh.harold.fulcrum.standard.profile;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record PlayerProfileUpserted(PlayerProfileSnapshot snapshot, Revision revision) {
    public PlayerProfileUpserted {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return "profile-upserted\n" + snapshot.wireValue(revision.value());
    }
}
