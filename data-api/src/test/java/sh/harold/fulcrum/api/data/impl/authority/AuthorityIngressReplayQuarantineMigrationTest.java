package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityIngressReplayQuarantineMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void replayQuarantineLivesInFlattenedSchemaMigration() {
        assertThat(FulcrumDataMigrations.all())
            .containsExactly(SCHEMA_MIGRATION);
    }

    @Test
    void replayQuarantineMigrationAllowsTerminalQuarantineStatus() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("CREATE TABLE IF NOT EXISTS authority_command_ingress_log")
            .contains("CONSTRAINT chk_authority_command_ingress_log_status")
            .contains("'QUARANTINED'")
            .contains("'authority_command_ingress_log'")
            .contains("quarantined replay refusals");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityIngressReplayQuarantineMigrationTest.class.getClassLoader()
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
