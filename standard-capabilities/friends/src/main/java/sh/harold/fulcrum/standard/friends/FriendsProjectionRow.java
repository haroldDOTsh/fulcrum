package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;

public record FriendsProjectionRow(FriendConnectionSnapshot snapshot, Revision revision) {
    public FriendsProjectionRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        revision = Objects.requireNonNull(revision, "revision");
    }

    public FriendConnectionId connectionId() {
        return snapshot.connectionId();
    }
}
