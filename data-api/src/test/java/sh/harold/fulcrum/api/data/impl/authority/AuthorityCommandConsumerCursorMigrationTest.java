package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandConsumerCursorMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void migrationAddsCommandConsumerCursorTable() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("CREATE TABLE IF NOT EXISTS authority_command_consumer_cursors")
            .contains("committed_offset BIGINT NOT NULL DEFAULT -1")
            .contains("last_command_id UUID NOT NULL")
            .contains("writer_claim_id UUID NOT NULL")
            .contains("chk_authority_command_consumer_cursors_offset")
            .contains("idx_authority_command_consumer_cursors_owner");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityCommandConsumerCursorMigrationTest.class.getClassLoader()
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
