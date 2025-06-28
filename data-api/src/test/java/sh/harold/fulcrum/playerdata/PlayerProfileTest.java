package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.data.registry.PlayerProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerProfileTest {
    static final UUID PLAYER_ID = UUID.randomUUID();
    static final Map<UUID, String> jsonBacking = new HashMap<>();
    static final Map<UUID, TestSqlData> sqlBacking = new HashMap<>();

    @BeforeEach
    void setup() {
        jsonBacking.clear();
        sqlBacking.clear();
        jsonBacking.put(PLAYER_ID, "foo");
        sqlBacking.put(PLAYER_ID, new TestSqlData(42));
        PlayerDataRegistry.clear();
    }

    @Test
    void testProfileLifecycle() {
        var jsonSchema = new TestJsonSchema();
        var sqlSchema = new TestSqlSchema();
        var jsonBackend = new DummyJsonBackend();
        var sqlBackend = new DummySqlBackend();
        PlayerDataRegistry.registerSchema(jsonSchema, jsonBackend);
        PlayerDataRegistry.registerSchema(sqlSchema, sqlBackend);
        var profile = new PlayerProfile(PLAYER_ID);
        profile.loadAll();
        assertEquals(new TestJsonData("foo"), profile.get(TestJsonData.class));
        assertEquals(new TestSqlData(42), profile.get(TestSqlData.class));
        profile.set(TestJsonData.class, new TestJsonData("bar"));
        profile.set(TestSqlData.class, new TestSqlData(99));
        profile.saveAll();
        assertEquals("bar", jsonBacking.get(PLAYER_ID));
        assertEquals(new TestSqlData(99), sqlBacking.get(PLAYER_ID));
    }

    static class TestJsonData {
        final String value;

        TestJsonData(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TestJsonData t && Objects.equals(value, t.value);
        }
    }

    static class TestJsonSchema extends JsonSchema<TestJsonData> {
        @Override
        public String schemaKey() {
            return "test_json";
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

    static class TestSqlData {
        final int number;

        TestSqlData(int number) {
            this.number = number;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TestSqlData t && number == t.number;
        }
    }

    static class TestSqlSchema extends TableSchema<TestSqlData> {
        @Override
        public String schemaKey() {
            return "test_sql";
        }

        @Override
        public Class<TestSqlData> type() {
            return TestSqlData.class;
        }
    }

    static class DummyJsonBackend implements PlayerDataBackend {
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return (T) new TestJsonData(jsonBacking.get(uuid));
        }

        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            jsonBacking.put(uuid, ((TestJsonData) data).value);
        }
    }

    static class DummySqlBackend implements PlayerDataBackend {
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return (T) sqlBacking.get(uuid);
        }

        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            sqlBacking.put(uuid, (TestSqlData) data);
        }
    }
}
