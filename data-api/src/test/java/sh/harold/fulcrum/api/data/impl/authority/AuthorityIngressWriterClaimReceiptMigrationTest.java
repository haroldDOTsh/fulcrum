package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityIngressWriterClaimReceiptMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    @Test
    void writerClaimReceiptsLiveInFlattenedSchemaMigration() {
        assertThat(FulcrumDataMigrations.all())
            .containsExactly(SCHEMA_MIGRATION);
    }

    @Test
    void writerClaimReceiptMigrationAddsReceiptColumnsAndIndexes() {
        String sql = readResource(SCHEMA_MIGRATION);

        assertThat(sql)
            .contains("writer_claim_epoch BIGINT")
            .contains("writer_claim_id UUID")
            .contains("writer_claim_fingerprint TEXT")
            .contains("chk_authority_command_ingress_log_writer_claim_receipt")
            .contains("idx_authority_command_ingress_log_writer_claim_id")
            .contains("idx_authority_command_ingress_log_writer_claim_fingerprint")
            .contains("idx_authority_command_ingress_log_writer_claim_epoch");
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityIngressWriterClaimReceiptMigrationTest.class.getClassLoader()
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
