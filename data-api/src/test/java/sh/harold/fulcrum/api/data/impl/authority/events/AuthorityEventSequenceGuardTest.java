package sh.harold.fulcrum.api.data.impl.authority.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityEventSequenceGuardTest {
    @Test
    void acceptsFirstRevisionWhenNoCheckpointExists() {
        AuthorityEventSequenceGuard.SequenceVerdict verdict =
            AuthorityEventSequenceGuard.verify("rank-projection", event(1L), null);

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void rejectsMissingInitialRevision() {
        AuthorityEventSequenceGuard.SequenceVerdict verdict =
            AuthorityEventSequenceGuard.verify("rank-projection", event(3L), null);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.message()).contains("no checkpoint", "revision is 3");
    }

    @Test
    void acceptsNextContiguousRevision() {
        AuthorityEventSequenceGuard.SequenceVerdict verdict =
            AuthorityEventSequenceGuard.verify("rank-projection", event(4L), 3L);

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void rejectsDuplicateOrRewoundRevision() {
        AuthorityEventSequenceGuard.SequenceVerdict duplicate =
            AuthorityEventSequenceGuard.verify("rank-projection", event(3L), 3L);
        AuthorityEventSequenceGuard.SequenceVerdict rewind =
            AuthorityEventSequenceGuard.verify("rank-projection", event(2L), 3L);

        assertThat(duplicate.accepted()).isFalse();
        assertThat(duplicate.message()).contains("already checkpointed", "revision 3");
        assertThat(rewind.accepted()).isFalse();
        assertThat(rewind.message()).contains("already checkpointed", "revision 2");
    }

    @Test
    void rejectsSkippedRevision() {
        AuthorityEventSequenceGuard.SequenceVerdict verdict =
            AuthorityEventSequenceGuard.verify("rank-projection", event(5L), 3L);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.message()).contains("expected rank:player:", "revision 4", "revision 5");
    }

    @Test
    void rejectsNonPositiveRevision() {
        AuthorityEventSequenceGuard.SequenceVerdict verdict =
            AuthorityEventSequenceGuard.verify("rank-projection", event(0L), null);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.message()).contains("non-positive revision 0");
    }

    private static AuthorityEventEnvelope event(long revision) {
        UUID playerId = UUID.randomUUID();
        return new AuthorityEventEnvelope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            revision,
            "GRANT_RANK",
            Map.of("primaryRank", "ADMIN"),
            Map.of("originNode", "authority-test"),
            Instant.ofEpochMilli(1_800_000_000_000L + revision)
        );
    }
}
