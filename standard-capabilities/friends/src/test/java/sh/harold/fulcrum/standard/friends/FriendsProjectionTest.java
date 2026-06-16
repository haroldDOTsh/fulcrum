package sh.harold.fulcrum.standard.friends;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FriendsProjectionTest {
    private static final Instant NOW = Instant.parse("2026-06-16T21:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-friends-test");
    private static final SubjectId FIRST = subject("00000000-0000-0000-0000-000000000901");
    private static final SubjectId SECOND = subject("00000000-0000-0000-0000-000000000902");
    private static final SubjectId THIRD = subject("00000000-0000-0000-0000-000000000903");

    @Test
    void projectionIndexesAcceptedInvitesBidirectionally() {
        FriendsProjection projection = FriendsProjection.rebuild(List.of(
                new FriendInviteAccepted(snapshot(FIRST, SECOND), new Revision(1)),
                new FriendInviteAccepted(snapshot(FIRST, THIRD), new Revision(1))));

        assertEquals(List.of(SECOND, THIRD), projection.friendsOf(FIRST));
        assertEquals(List.of(FIRST), projection.friendsOf(SECOND));
        assertEquals(List.of(FIRST), projection.friendsOf(THIRD));
    }

    @Test
    void canonicalConnectionIdIsStableAcrossInviteDirection() {
        assertEquals(FriendConnectionId.from(FIRST, SECOND), FriendConnectionId.from(SECOND, FIRST));
    }

    @Test
    void replayRequiresIncreasingConnectionRevision() {
        assertThrows(IllegalArgumentException.class, () -> FriendsProjection.rebuild(List.of(
                new FriendInviteAccepted(snapshot(FIRST, SECOND), new Revision(2)),
                new FriendInviteAccepted(snapshot(FIRST, SECOND), new Revision(2)))));
    }

    private static FriendConnectionSnapshot snapshot(SubjectId requester, SubjectId accepter) {
        return FriendConnectionSnapshot.accepted(requester, accepter, PRINCIPAL, NOW);
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }
}
