package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandGuardEvidenceMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void guardEvidenceLivesInFlattenedSchemaMigration() {
        assertThat(FulcrumDataMigrations.all())
            .containsExactly(SCHEMA_MIGRATION);
    }

    @Test
    void guardEvidenceMigrationAddsEvidenceAndFingerprintColumns() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("CREATE TABLE IF NOT EXISTS authority_command_ingress_log")
            .contains("CREATE TABLE IF NOT EXISTS authority_command_refusal_log")
            .contains("guard_evidence JSONB NOT NULL DEFAULT '{}'::jsonb")
            .contains("guard_evidence_fingerprint TEXT NOT NULL")
            .contains("chk_authority_command_ingress_log_guard_evidence_fingerprint")
            .contains("chk_authority_command_refusal_log_guard_evidence_fingerprint")
            .contains("idx_authority_command_ingress_log_guard_evidence")
            .contains("idx_authority_command_refusal_log_guard_evidence");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityCommandGuardEvidenceMigrationTest.class.getClassLoader()
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
