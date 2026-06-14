package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresAuthorityStateRestoreDrillTest {
    @Test
    void restoredResultReportsSourceRevisionAsPostRestoreSnapshotRevision() {
        PostgresAuthorityStateRestoreDrill.RestoreEvidence evidence =
            new PostgresAuthorityStateRestoreDrill.RestoreEvidence(
                2,
                "schema-fingerprint",
                "CHANGELOG_ONLY",
                "source-state-fingerprint",
                null,
                "source-event-chain-hash"
            );
        PostgresAuthorityStateRestoreDrill.RestoreRunResult missingSnapshot =
            new PostgresAuthorityStateRestoreDrill.RestoreRunResult(
                UUID.randomUUID(),
                "rank:player:" + UUID.randomUUID(),
                PostgresAuthorityStateRestoreDrill.Status.SNAPSHOT_MISSING,
                UUID.randomUUID(),
                UUID.randomUUID(),
                7L,
                null,
                false,
                "Snapshot is missing but changelog source exists",
                evidence
            );

        PostgresAuthorityStateRestoreDrill.RestoreRunResult restored =
            missingSnapshot.restored("Snapshot restored from authority state changelog");

        assertThat(restored.status()).isEqualTo(PostgresAuthorityStateRestoreDrill.Status.RESTORED);
        assertThat(restored.sourceRevision()).isEqualTo(7L);
        assertThat(restored.snapshotRevision()).isEqualTo(7L);
        assertThat(restored.restored()).isTrue();
        assertThat(restored.evidence().restoreSource()).isEqualTo("CHANGELOG_RESTORED");
        assertThat(restored.evidence().snapshotStateFingerprint()).isEqualTo("source-state-fingerprint");
    }
}
