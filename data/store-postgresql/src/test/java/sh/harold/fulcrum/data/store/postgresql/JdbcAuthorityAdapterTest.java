package sh.harold.fulcrum.data.store.postgresql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JdbcAuthorityAdapterTest {
    @Test
    void configsAcceptSimpleAndQualifiedTableNames() {
        assertEquals("authority_records", new JdbcAuthorityRecordStoreConfig("authority_records").tableName());
        assertEquals("fulcrum.authority_decisions", new JdbcAuthorityDecisionRecorderConfig("fulcrum.authority_decisions").tableName());
    }

    @Test
    void configsRejectSqlFragments() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcAuthorityRecordStoreConfig("authority_records where 1=1"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcAuthorityDecisionRecorderConfig("fulcrum.authority_decisions;drop"));
    }
}
