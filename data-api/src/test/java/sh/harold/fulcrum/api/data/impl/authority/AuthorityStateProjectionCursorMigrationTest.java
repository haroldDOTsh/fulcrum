package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityStateProjectionCursorMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void migrationAddsStateProjectionCursorTable() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("CREATE TABLE IF NOT EXISTS authority_state_projection_cursors")
            .contains("projection_version TEXT NOT NULL")
            .contains("state_topic TEXT NOT NULL")
            .contains("committed_offset BIGINT NOT NULL DEFAULT -1")
            .contains("last_event_id UUID NOT NULL")
            .contains("PRIMARY KEY (projection_name, projection_version, command_domain, state_partition)")
            .contains("idx_authority_state_projection_cursors_topic_partition");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityStateProjectionCursorMigrationTest.class.getClassLoader()
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
