package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

@Table("example_table")
record ExampleRecord(
    @Column(primary = true) UUID id,
    @Column String name,
    @Column int level,
    @Column boolean active
) {}

class AutoTableSchemaTest {
    @Test
    void generatesCorrectCreateTableSql() {
        var schema = new AutoTableSchema<>(ExampleRecord.class);
        String expected = "CREATE TABLE example_table (id UUID, name TEXT, level INT, active BOOLEAN, PRIMARY KEY (id));";
        String actual = schema.getCreateTableSql();
        System.out.println("Generated SQL: " + actual);
        assertEquals(expected, actual);
    }
}
