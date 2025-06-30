package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.annotation.Column;
import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.backend.sql.SqlDialectProvider;
import sh.harold.fulcrum.api.data.backend.sql.SqliteDialect;
import sh.harold.fulcrum.api.data.impl.Table;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Table("test_table")
class DialectTestPojo {
    @Column(primary = true)
    public UUID id;
    public String name;
    public int score;
    public boolean active;
}

class AutoTableSchemaDialectTest {
    @BeforeEach
    void setup() {
        SqlDialectProvider.setDialect(new SqliteDialect());
    }

    @Test
    void testCreateTableSqlUsesDialect() {
        AutoTableSchema<DialectTestPojo> schema = new AutoTableSchema<>(DialectTestPojo.class, null);
        String sql = schema.getCreateTableSql();
        assertTrue(sql.contains("`test_table`"));
        assertTrue(sql.contains("`id` TEXT"));
        assertTrue(sql.contains("`name` TEXT"));
        assertTrue(sql.contains("`score` INTEGER"));
        assertTrue(sql.contains("`active` BOOLEAN"));
        assertTrue(sql.contains("PRIMARY KEY (`id`)"));
    }

    @Test
    void testLegacyConstructorFallback() {
        AutoTableSchema<DialectTestPojo> schema = new AutoTableSchema<>(DialectTestPojo.class, null);
        String sql = schema.getCreateTableSql();
        assertTrue(sql.contains("`test_table`")); // fallback: SqliteDialect always quotes
        assertTrue(sql.contains("`id` TEXT"));
    }
}
