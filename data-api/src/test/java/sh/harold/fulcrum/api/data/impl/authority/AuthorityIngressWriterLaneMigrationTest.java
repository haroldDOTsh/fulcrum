package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityIngressWriterLaneMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void writerLaneEvidenceLivesInFlattenedSchemaMigration() {
        assertThat(FulcrumDataMigrations.all())
            .containsExactly(SCHEMA_MIGRATION);
    }

    @Test
    void writerLaneMigrationAddsReplayLaneColumnsAndIndexes() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("writer_lane_count INTEGER NOT NULL")
            .contains("writer_lane INTEGER NOT NULL")
            .contains("writer_lane_key_fingerprint TEXT NOT NULL")
            .contains("writer_lane_fencing_scope TEXT NOT NULL")
            .contains("chk_authority_command_ingress_log_writer_lane_bounds")
            .contains("chk_authority_command_ingress_log_writer_lane_fingerprint")
            .contains("idx_authority_command_ingress_log_writer_lane_replay")
            .contains("idx_authority_command_ingress_log_writer_lane_time");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityIngressWriterLaneMigrationTest.class.getClassLoader()
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
