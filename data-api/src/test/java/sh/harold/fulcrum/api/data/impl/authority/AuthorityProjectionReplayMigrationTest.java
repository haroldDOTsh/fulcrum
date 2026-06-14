package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityProjectionReplayMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void replayEvidenceLivesInFlattenedSchemaMigration() {
        assertThat(FulcrumDataMigrations.all())
            .containsExactly(SCHEMA_MIGRATION);
    }

    @Test
    void replaySkipMigrationAddsSummaryColumnAndVerdict() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("skipped_events INTEGER NOT NULL DEFAULT 0")
            .contains("'SKIPPED_BY_MANIFEST'")
            .contains("chk_authority_projection_replay_run_events_verdict");
    }

    @Test
    void replayReceiptMigrationAddsSchemaAndFingerprintEvidence() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("schema_contract_version")
            .contains("schema_contract_fingerprint")
            .contains("projection_manifest_fingerprint")
            .contains("replay_source")
            .contains("event_range_fingerprint")
            .contains("verification_fingerprint")
            .contains("idx_authority_projection_replay_runs_verification");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityProjectionReplayMigrationTest.class.getClassLoader()
            .getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, exception);
        }
    }
}
