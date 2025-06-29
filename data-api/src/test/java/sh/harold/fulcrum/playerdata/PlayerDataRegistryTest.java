package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDataRegistryTest {
    @BeforeEach
    void clearRegistry() {
        PlayerDataRegistry.clear();
    }

    @Test
    void testRegisterAndGet() {
        var jsonSchema = new TestJsonSchema();
        var sqlSchema = new TestSqlSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(jsonSchema, backend);
        PlayerDataRegistry.registerSchema(sqlSchema, backend);
        assertTrue(PlayerDataRegistry.allSchemas().contains(jsonSchema));
        assertTrue(PlayerDataRegistry.allSchemas().contains(sqlSchema));
    }

    @Test
    void testAllSchemas() {
        var jsonSchema = new TestJsonSchema();
        var sqlSchema = new TestSqlSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(jsonSchema, backend);
        PlayerDataRegistry.registerSchema(sqlSchema, backend);
        var all = PlayerDataRegistry.allSchemas();
        assertTrue(all.contains(jsonSchema));
        assertTrue(all.contains(sqlSchema));
        assertEquals(2, all.size());
    }

    @Test
    void testClear() {
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(new TestJsonSchema(), backend);
        assertFalse(PlayerDataRegistry.allSchemas().isEmpty());
        PlayerDataRegistry.clear();
        assertTrue(PlayerDataRegistry.allSchemas().isEmpty());
    }

    static class TestJsonData {
    }

    static class TestSqlData {
    }

    static class TestJsonSchema extends JsonSchema<TestJsonData> {
        @Override
        public String schemaKey() {
            return "json";
        }

        @Override
        public Class<TestJsonData> type() {
            return TestJsonData.class;
        }

        @Override
        public TestJsonData deserialize(UUID uuid, String json) {
            return null;
        }

        @Override
        public String serialize(UUID uuid, TestJsonData data) {
            return null;
        }
    }

    static class TestSqlSchema extends TableSchema<TestSqlData> {
        @Override
        public String schemaKey() {
            return "sql";
        }

        @Override
        public Class<TestSqlData> type() {
            return TestSqlData.class;
        }
    }

    static class DummyBackend implements PlayerDataBackend {
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return null;
        }

        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
        }

        @Override
        public <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema) {
            return null;
        }
    }
}
