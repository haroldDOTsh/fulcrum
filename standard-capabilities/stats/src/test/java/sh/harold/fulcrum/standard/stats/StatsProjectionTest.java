package sh.harold.fulcrum.standard.stats;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StatsProjectionTest {
    private static final Instant NOW = Instant.parse("2026-06-17T00:15:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000001401"));
    private static final StatsCounterId COUNTER_ID = new StatsCounterId(SUBJECT, "session-completions");
    private static final ExperienceId ARENA = new ExperienceId("experience.stats.arena");
    private static final ExperienceId REALM = new ExperienceId("experience.stats.realm");
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-stats-projection");

    @Test
    void rebuildsNetworkAndExperienceTotalsFromLedgerEntries() {
        StatsProjection projection = StatsProjection.rebuild(List.of(
                event("entry-1", ARENA, 1, 1, 1),
                event("entry-2", REALM, 1, 2, 2),
                event("entry-3", ARENA, 1, 3, 3)));

        assertEquals(3, projection.counter(COUNTER_ID).orElseThrow().total());
        assertEquals(2, projection.experienceCounter(COUNTER_ID, ARENA).orElseThrow().total());
        assertEquals(1, projection.experienceCounter(COUNTER_ID, REALM).orElseThrow().total());
    }

    @Test
    void rejectsLedgerEntryThatDoesNotMatchRunningTotal() {
        List<StatsDeltaRecorded> events = List.of(
                event("entry-1", ARENA, 1, 1, 1),
                event("entry-2", REALM, 1, 5, 2));

        assertThrows(IllegalArgumentException.class, () -> StatsProjection.rebuild(events));
    }

    private static StatsDeltaRecorded event(
            String entryId,
            ExperienceId experienceId,
            long delta,
            long resultingTotal,
            long revision) {
        Revision nextRevision = new Revision(revision);
        return new StatsDeltaRecorded(
                new StatsLedgerEntry(
                        entryId,
                        COUNTER_ID,
                        experienceId,
                        delta,
                        resultingTotal,
                        PRINCIPAL,
                        NOW.plusSeconds(revision),
                        "idem-" + entryId,
                        "command-" + entryId,
                        nextRevision),
                nextRevision);
    }
}
