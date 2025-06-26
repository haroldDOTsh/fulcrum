package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDataRegistryTest {
    static class TestJsonData {}
    static class TestSqlData {}

    static class TestJsonSchema extends JsonSchema<TestJsonData> {
        @Override public String schemaKey() { return "json"; }
        @Override public Class<TestJsonData> type() { return TestJsonData.class; }
        @Override public TestJsonData load(UUID uuid) { return null; }
        @Override public void save(UUID uuid, TestJsonData data) {}
        @Override public TestJsonData deserialize(UUID uuid, String json) { return null; }
        @Override public String serialize(UUID uuid, TestJsonData data) { return null; }
    }
    static class TestSqlSchema extends TableSchema<TestSqlData> {
        @Override public String schemaKey() { return "sql"; }
        @Override public Class<TestSqlData> type() { return TestSqlData.class; }
        @Override public TestSqlData load(UUID uuid) { return null; }
        @Override public void save(UUID uuid, TestSqlData data) {}
    }

    @BeforeEach
    void clearRegistry() {
        PlayerDataRegistry.clear();
    }

    @Test
    void testRegisterAndGet() {
        var jsonSchema = new TestJsonSchema();
        var sqlSchema = new TestSqlSchema();
        PlayerDataRegistry.register(jsonSchema);
        PlayerDataRegistry.register(sqlSchema);
        assertSame(jsonSchema, PlayerDataRegistry.get(TestJsonData.class));
        assertSame(sqlSchema, PlayerDataRegistry.get(TestSqlData.class));
    }

    @Test
    void testAllSchemas() {
        var jsonSchema = new TestJsonSchema();
        var sqlSchema = new TestSqlSchema();
        PlayerDataRegistry.register(jsonSchema);
        PlayerDataRegistry.register(sqlSchema);
        var all = PlayerDataRegistry.allSchemas();
        assertTrue(all.contains(jsonSchema));
        assertTrue(all.contains(sqlSchema));
        assertEquals(2, all.size());
    }

    @Test
    void testClear() {
        PlayerDataRegistry.register(new TestJsonSchema());
        assertFalse(PlayerDataRegistry.allSchemas().isEmpty());
        PlayerDataRegistry.clear();
        assertTrue(PlayerDataRegistry.allSchemas().isEmpty());
    }
}
