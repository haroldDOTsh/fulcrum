package sh.harold.fulcrum.standard.friends;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.Objects;
import java.util.Optional;

public record FriendsState(Optional<FriendConnectionSnapshot> current) {
    public FriendsState(FriendConnectionSnapshot current) {
        this(Optional.of(Objects.requireNonNull(current, "current")));
    }

    public FriendsState {
        current = current == null ? Optional.empty() : current;
    }

    public static FriendsState empty() {
        return new FriendsState(Optional.empty());
    }

    public String wireValue(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        return current.map(snapshot -> snapshot.wireValue(revision))
                .orElse("empty=true\nrevision=" + revision.value());
    }
}
