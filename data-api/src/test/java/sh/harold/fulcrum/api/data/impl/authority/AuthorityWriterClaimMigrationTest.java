package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityWriterClaimMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void migrationAddsWriterClaimReceiptsAndCurrentClaimHead() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(FulcrumDataMigrations.all()).containsExactly(SCHEMA_MIGRATION);
        assertThat(sql)
            .contains("CREATE TABLE IF NOT EXISTS authority_writer_claims")
            .contains("last_claim_id UUID")
            .contains("last_claim_fingerprint TEXT")
            .contains("claim_fingerprint TEXT NOT NULL")
            .contains("FOREIGN KEY (command_domain, partition_key)")
            .contains("'authority_writer_claims'")
            .contains("ON authority_writer_claims USING BRIN (claimed_at)");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityWriterClaimMigrationTest.class.getClassLoader()
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
