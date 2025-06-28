package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.JsonSchema;
import sh.harold.fulcrum.api.PlayerDataSchema;
import sh.harold.fulcrum.api.TableSchema;
import sh.harold.fulcrum.backend.PlayerDataBackend;
import sh.harold.fulcrum.registry.PlayerDataRegistry;
import sh.harold.fulcrum.registry.PlayerStorageManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerStorageManagerTest {
    static final Map<UUID, TestData> jsonMap = new HashMap<>();
    static final Map<UUID, TestData> sqlMap = new HashMap<>();
    static final UUID PLAYER_ID = UUID.randomUUID();

    @BeforeEach
    void reset() {
        jsonMap.clear();
        sqlMap.clear();
        PlayerDataRegistry.clear();
    }

    @Test
    void testLoadAndSave() {
        var jsonSchema = new DummyJsonSchema();
        var sqlSchema = new DummySqlSchema();
        var jsonBackend = new DummyJsonBackend();
        var sqlBackend = new DummySqlBackend();
        PlayerDataRegistry.registerSchema(jsonSchema, jsonBackend);
        PlayerDataRegistry.registerSchema(sqlSchema, sqlBackend);
        var data1 = new TestData("foo");
        var data2 = new TestData("bar");
        PlayerStorageManager.save(PLAYER_ID, jsonSchema, data1);
        PlayerStorageManager.save(PLAYER_ID, sqlSchema, data2);
        assertEquals(data1, PlayerStorageManager.load(PLAYER_ID, jsonSchema));
        assertEquals(data2, PlayerStorageManager.load(PLAYER_ID, sqlSchema));
    }

    record TestData(String value) {
    }

    static class DummyJsonSchema extends JsonSchema<TestData> {
        @Override
        public String schemaKey() {
            return "json";
        }

        @Override
        public Class<TestData> type() {
            return TestData.class;
        }

        @Override
        public TestData deserialize(UUID uuid, String json) {
            return null;
        }

        @Override
        public String serialize(UUID uuid, TestData data) {
            return null;
        }
    }

    static class DummySqlSchema extends TableSchema<TestData> {
        @Override
        public String schemaKey() {
            return "sql";
        }

        @Override
        public Class<TestData> type() {
            return TestData.class;
        }
    }

    static class DummyJsonBackend implements PlayerDataBackend {
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return (T) jsonMap.get(uuid);
        }

        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            jsonMap.put(uuid, (TestData) data);
        }
    }

    static class DummySqlBackend implements PlayerDataBackend {
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return (T) sqlMap.get(uuid);
        }

        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            sqlMap.put(uuid, (TestData) data);
        }
    }
}
