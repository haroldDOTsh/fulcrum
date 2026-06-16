package sh.harold.fulcrum.data.store.postgresql;

public record JdbcAuthorityRecordStoreConfig(String tableName) {
    public JdbcAuthorityRecordStoreConfig {
        tableName = SqlIdentifier.requireQualifiedIdentifier(tableName, "tableName");
    }
}
