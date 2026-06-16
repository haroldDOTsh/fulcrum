package sh.harold.fulcrum.validation.standardcapabilities;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.rank.EffectiveRankProjection;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.RankGranted;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectionRebuildRtoDrillTest {
    private static final Instant NOW = Instant.parse("2026-06-16T20:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("projection-rebuild-drill");
    private static final int SUBJECT_COUNT = 256;
    private static final int UPDATES_PER_SUBJECT = 8;
    private static final Duration RTO_BUDGET = Duration.ofSeconds(5);

    @Test
    void rankProjectionRebuildReportsElapsedTimeAgainstRtoBudget() {
        List<RankGranted> events = rankGrantedEvents();
        EffectiveRankProjection expected = EffectiveRankProjection.rebuild(events);
        EffectiveRankProjection clearedProjection = EffectiveRankProjection.empty();

        long started = System.nanoTime();
        EffectiveRankProjection rebuilt = EffectiveRankProjection.rebuild(events);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        ProjectionRebuildReport report = new ProjectionRebuildReport(
                RankContracts.EFFECTIVE_PROJECTION,
                events.size(),
                rebuilt.rows().size(),
                elapsed,
                RTO_BUDGET);

        assertTrue(clearedProjection.rows().isEmpty());
        assertEquals(expected.rows(), rebuilt.rows());
        assertEquals(SUBJECT_COUNT * UPDATES_PER_SUBJECT, report.eventCount());
        assertEquals(SUBJECT_COUNT, report.rowCount());
        assertTrue(!report.elapsed().isNegative());
        assertTrue(report.withinBudget(), report::evidenceLine);
    }

    private static List<RankGranted> rankGrantedEvents() {
        List<RankGranted> events = new ArrayList<>(SUBJECT_COUNT * UPDATES_PER_SUBJECT);
        for (int subjectIndex = 0; subjectIndex < SUBJECT_COUNT; subjectIndex++) {
            SubjectId subjectId = new SubjectId(new UUID(0L, subjectIndex + 1L));
            for (int revision = 1; revision <= UPDATES_PER_SUBJECT; revision++) {
                events.add(granted(subjectId, revision));
            }
        }
        return List.copyOf(events);
    }

    private static RankGranted granted(SubjectId subjectId, int revision) {
        EffectiveRankSnapshot snapshot = new EffectiveRankSnapshot(
                subjectId,
                "rank-" + revision,
                "rank:rank-" + revision,
                PRINCIPAL,
                NOW.plusSeconds(revision));
        return new RankGranted(snapshot, new Revision(revision));
    }

    private record ProjectionRebuildReport(
            String projectionName,
            int eventCount,
            int rowCount,
            Duration elapsed,
            Duration budget) {
        private boolean withinBudget() {
            return elapsed.compareTo(budget) <= 0;
        }

        private String evidenceLine() {
            return "projection rebuild RTO exceeded for %s: events=%d rows=%d elapsed=%s budget=%s"
                    .formatted(projectionName, eventCount, rowCount, elapsed, budget);
        }
    }
}
