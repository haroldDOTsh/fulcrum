package sh.harold.fulcrum.api.data.backend.sql;

public final class SqlDialectProvider {
    private static volatile SqlDialect currentDialect = new SqliteDialect(); // sensible default

    private SqlDialectProvider() {
    }

    public static void setDialect(SqlDialect dialect) {
        if (dialect != null) currentDialect = dialect;
    }

    public static SqlDialect get() {
        return currentDialect;
    }
}
