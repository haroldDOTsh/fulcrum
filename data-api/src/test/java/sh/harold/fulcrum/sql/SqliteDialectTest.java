package sh.harold.fulcrum.sql;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.backend.sql.SqliteDialect;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteDialectTest {
    private final SqliteDialect dialect = new SqliteDialect();

    @Test
    void testTypeMappings() {
        assertEquals("TEXT", dialect.getSqlType(UUID.class));
        assertEquals("TEXT", dialect.getSqlType(String.class));
        assertEquals("INTEGER", dialect.getSqlType(int.class));
        assertEquals("INTEGER", dialect.getSqlType(Integer.class));
        assertEquals("INTEGER", dialect.getSqlType(long.class));
        assertEquals("INTEGER", dialect.getSqlType(Long.class));
        assertEquals("BOOLEAN", dialect.getSqlType(boolean.class));
        assertEquals("BOOLEAN", dialect.getSqlType(Boolean.class));
        assertEquals("REAL", dialect.getSqlType(double.class));
        assertEquals("REAL", dialect.getSqlType(Double.class));
        assertEquals("REAL", dialect.getSqlType(float.class));
        assertEquals("REAL", dialect.getSqlType(Float.class));
    }

    @Test
    void testEnumTypeMapping() {
        // Test that enum types are mapped to TEXT
        assertEquals("TEXT", dialect.getSqlType(TestEnum.class));
    }

    @Test
    void testUnsupportedType() {
        // Test that unsupported types throw an exception
        assertThrows(IllegalArgumentException.class, () -> dialect.getSqlType(Object.class));
    }

    @Test
    void testIdentifierQuoting() {
        assertEquals("`foo`", dialect.quoteIdentifier("foo"));
        assertEquals("`foo``bar`", dialect.quoteIdentifier("foo`bar"));
    }

    @Test
    void testUpsertStatement() {
        String sql = dialect.getUpsertStatement("test_table", List.of("id", "name"), List.of("id"));
        assertEquals("INSERT OR REPLACE INTO `test_table` (`id`, `name`) VALUES (?, ?)", sql);
    }

    // Test enum for enum type mapping test
    enum TestEnum {
        VALUE1, VALUE2
    }
}
