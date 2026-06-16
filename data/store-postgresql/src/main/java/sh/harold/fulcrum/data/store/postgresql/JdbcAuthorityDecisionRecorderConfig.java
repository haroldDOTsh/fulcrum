package sh.harold.fulcrum.data.store.postgresql;

public record JdbcAuthorityDecisionRecorderConfig(String tableName) {
    public JdbcAuthorityDecisionRecorderConfig {
        tableName = SqlIdentifier.requireQualifiedIdentifier(tableName, "tableName");
    }
}
