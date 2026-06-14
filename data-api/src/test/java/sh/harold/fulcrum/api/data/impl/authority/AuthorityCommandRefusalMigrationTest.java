package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandRefusalMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void refusalLedgerLivesInFlattenedSchemaMigration() {
        assertThat(FulcrumDataMigrations.all())
            .containsExactly(SCHEMA_MIGRATION);
    }

    @Test
    void refusalMigrationCreatesLedgerAndAuditIndexes() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("CREATE TABLE IF NOT EXISTS authority_command_refusal_log")
            .contains("expected_contract_fingerprint")
            .contains("received_contract_fingerprint")
            .contains("replay_eligibility TEXT NOT NULL DEFAULT 'NOT_REPLAYABLE'")
            .contains("refusal_fingerprint")
            .contains("idx_authority_command_refusal_log_principal_time")
            .contains("idx_authority_command_refusal_log_reason_time")
            .contains("idx_authority_command_refusal_log_created_at_brin");
    }

    @Test
    void refusalMigrationRegistersLifecyclePolicy() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("'authority_command_refusal_log'")
            .contains("'created_at'")
            .contains("'APPEND_AUDIT'")
            .contains("'MONTHLY_RANGE'")
            .contains("'Pre-submit command refusal evidence");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityCommandRefusalMigrationTest.class.getClassLoader()
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
