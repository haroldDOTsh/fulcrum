package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record FriendInviteAccepted(FriendConnectionSnapshot snapshot, Revision revision) {
    public FriendInviteAccepted {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public String wireValue() {
        return snapshot.wireValue(revision);
    }
}
