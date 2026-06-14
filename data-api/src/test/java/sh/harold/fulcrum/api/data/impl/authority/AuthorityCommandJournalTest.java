package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandJournalTest {
    @Test
    void postgresIngressClassesImplementCommandJournalBoundary() {
        assertThat(AuthorityCommandJournal.Recorder.class)
            .isAssignableFrom(PostgresLoggedAuthorityCommandPort.class);
        assertThat(AuthorityCommandJournal.ReplayReader.class)
            .isAssignableFrom(PostgresAuthorityCommandIngressLog.class);
        assertThat(AuthorityCommandJournal.Entry.class)
            .isAssignableFrom(PostgresAuthorityCommandIngressLog.CommandIngressEntry.class);
        assertThat(AuthorityCommandJournal.Replay.class)
            .isAssignableFrom(PostgresAuthorityCommandIngressLog.ReplayResult.class);
    }

    @Test
    void postgresJournalStatusNamesRemainStable() {
        assertThat(PostgresAuthorityCommandIngressLog.CommandIngressStatus.values())
            .extracting(Enum::name)
            .containsExactly("RECEIVED", "APPLIED", "REJECTED", "FAILED", "QUARANTINED");
        assertThat(PostgresAuthorityCommandIngressLog.ReplayEligibility.values())
            .extracting(Enum::name)
            .containsExactly("REPLAYABLE", "NOT_REPLAYABLE");
    }
}
