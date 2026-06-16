package sh.harold.fulcrum.standard.party;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PartyRosterProjectionTest {
    private static final Instant NOW = Instant.parse("2026-06-16T20:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-party-test");
    private static final SubjectId LEADER = subject("00000000-0000-0000-0000-000000000801");
    private static final SubjectId MEMBER = subject("00000000-0000-0000-0000-000000000802");
    private static final SubjectId OTHER = subject("00000000-0000-0000-0000-000000000803");

    @Test
    void projectionIndexesPartyByEachSubjectForQueuePolicyUse() {
        PartyId partyId = new PartyId("party-alpha");
        PartyRosterProjection projection = PartyRosterProjection.rebuild(List.of(new PartyFormed(
                snapshot(partyId, LEADER, MEMBER),
                new Revision(1))));

        assertEquals(partyId, projection.partyFor(LEADER).orElseThrow());
        assertEquals(partyId, projection.partyFor(MEMBER).orElseThrow());
        assertEquals(List.of(LEADER, MEMBER), projection.membersFor(MEMBER));
    }

    @Test
    void subjectCannotBelongToTwoActivePartiesInOneProjection() {
        assertThrows(IllegalArgumentException.class, () -> PartyRosterProjection.rebuild(List.of(
                new PartyFormed(snapshot(new PartyId("party-alpha"), LEADER, MEMBER), new Revision(1)),
                new PartyFormed(snapshot(new PartyId("party-beta"), OTHER, MEMBER), new Revision(1)))));
    }

    @Test
    void replayRequiresIncreasingPartyRevision() {
        PartyId partyId = new PartyId("party-alpha");
        assertThrows(IllegalArgumentException.class, () -> PartyRosterProjection.rebuild(List.of(
                new PartyFormed(snapshot(partyId, LEADER, MEMBER), new Revision(2)),
                new PartyFormed(snapshot(partyId, LEADER, MEMBER), new Revision(2)))));
    }

    private static PartyRosterSnapshot snapshot(PartyId partyId, SubjectId leader, SubjectId member) {
        return new PartyRosterSnapshot(partyId, leader, List.of(leader, member), PRINCIPAL, NOW);
    }

    private static SubjectId subject(String uuid) {
        return new SubjectId(UUID.fromString(uuid));
    }
}
