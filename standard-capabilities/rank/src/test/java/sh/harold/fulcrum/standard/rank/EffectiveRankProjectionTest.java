package sh.harold.fulcrum.standard.rank;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.standard.contracts.RankContracts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EffectiveRankProjectionTest {
    private static final Instant NOW = Instant.parse("2026-06-16T15:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-rank-authority");
    private static final SubjectId SUBJECT_ONE = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000301"));
    private static final SubjectId SUBJECT_TWO = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000302"));

    @Test
    void rebuildsLatestEffectiveRankRowsBySubject() {
        RankGranted firstSubjectInitial = granted(SUBJECT_ONE, "member", 1);
        RankGranted secondSubject = granted(SUBJECT_TWO, "moderator", 1);
        RankGranted firstSubjectUpdated = granted(SUBJECT_ONE, "admin", 2);

        EffectiveRankProjection projection = EffectiveRankProjection.rebuild(List.of(
                firstSubjectInitial,
                secondSubject,
                firstSubjectUpdated));

        assertEquals(new EffectiveRankProjectionRow(firstSubjectUpdated.snapshot(), new Revision(2)),
                projection.row(SUBJECT_ONE).orElseThrow());
        assertEquals(new EffectiveRankProjectionRow(secondSubject.snapshot(), new Revision(1)),
                projection.row(SUBJECT_TWO).orElseThrow());
        assertEquals(2, projection.rows().size());
    }

    @Test
    void projectionRowUsesDeclaredEffectiveRankRelationAndSnapshotWireValue() {
        RankGranted event = granted(SUBJECT_ONE, "admin", 3);

        EffectiveRankProjectionRow row = EffectiveRankProjection.rebuild(List.of(event))
                .row(SUBJECT_ONE)
                .orElseThrow();

        assertEquals(RankContracts.EFFECTIVE_PROJECTION + ":" + SUBJECT_ONE.value(), row.key());
        assertEquals(event.snapshot().wireValue(3), row.wireValue());
    }

    @Test
    void rejectsNonIncreasingRevisionsForSameSubject() {
        RankGranted first = granted(SUBJECT_ONE, "member", 2);
        RankGranted duplicateOrOld = granted(SUBJECT_ONE, "admin", 2);

        assertThrows(IllegalArgumentException.class,
                () -> EffectiveRankProjection.rebuild(List.of(first, duplicateOrOld)));
    }

    @Test
    void rejectsRowsKeyedByTheWrongSubject() {
        EffectiveRankProjectionRow row = new EffectiveRankProjectionRow(granted(SUBJECT_ONE, "admin", 1).snapshot(), new Revision(1));

        assertThrows(IllegalArgumentException.class,
                () -> new EffectiveRankProjection(Map.of(SUBJECT_TWO, row)));
    }

    @Test
    void emptyReplayProducesNoRows() {
        assertTrue(EffectiveRankProjection.empty().rows().isEmpty());
        assertTrue(EffectiveRankProjection.rebuild(List.of()).rows().isEmpty());
    }

    private static RankGranted granted(SubjectId subjectId, String rankKey, long revision) {
        EffectiveRankSnapshot snapshot = new EffectiveRankSnapshot(
                subjectId,
                rankKey,
                "rank:" + rankKey,
                PRINCIPAL,
                NOW.plusSeconds(revision));
        return new RankGranted(snapshot, new Revision(revision));
    }
}
