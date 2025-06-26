package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileTest {
    static final UUID PLAYER_ID = UUID.randomUUID();
    static final Map<UUID, String> jsonBacking = new HashMap<>();
    static final Map<UUID, TestSqlData> sqlBacking = new HashMap<>();

    static class TestJsonData {
        final String value;
        TestJsonData(String value) { this.value = value; }
        @Override public boolean equals(Object o) {
            return o instanceof TestJsonData t && Objects.equals(value, t.value);
        }
    }

    static class TestJsonSchema extends JsonSchema<TestJsonData> {
        @Override public String schemaKey() { return "test_json"; }
        @Override public Class<TestJsonData> type() { return TestJsonData.class; }
        @Override public TestJsonData load(UUID uuid) {
            var json = jsonBacking.get(uuid);
            return json == null ? null : new TestJsonData(json);
        }
        @Override public void save(UUID uuid, TestJsonData data) {
            jsonBacking.put(uuid, data.value);
        }
        @Override public TestJsonData deserialize(UUID uuid, String json) { return null; }
        @Override public String serialize(UUID uuid, TestJsonData data) { return null; }
    }

    static class TestSqlData {
        final int number;
        TestSqlData(int number) { this.number = number; }
        @Override public boolean equals(Object o) {
            return o instanceof TestSqlData t && number == t.number;
        }
    }

    static class TestSqlSchema extends TableSchema<TestSqlData> {
        @Override public String schemaKey() { return "test_sql"; }
        @Override public Class<TestSqlData> type() { return TestSqlData.class; }
        @Override public TestSqlData load(UUID uuid) { return sqlBacking.get(uuid); }
        @Override public void save(UUID uuid, TestSqlData data) { sqlBacking.put(uuid, data); }
    }

    @BeforeEach
    void setup() {
        jsonBacking.clear();
        sqlBacking.clear();
        jsonBacking.put(PLAYER_ID, "foo");
        sqlBacking.put(PLAYER_ID, new TestSqlData(42));
    }

    @Test
    void testProfileLifecycle() {
        var registry = new PlayerDataRegistry();
        registry.register(new TestJsonSchema());
        registry.register(new TestSqlSchema());
        var profile = new PlayerProfile(PLAYER_ID, registry);
        profile.loadAll();
        assertEquals(new TestJsonData("foo"), profile.get(TestJsonData.class));
        assertEquals(new TestSqlData(42), profile.get(TestSqlData.class));
        profile.set(TestJsonData.class, new TestJsonData("bar"));
        profile.set(TestSqlData.class, new TestSqlData(99));
        profile.saveAll();
        assertEquals("bar", jsonBacking.get(PLAYER_ID));
        assertEquals(new TestSqlData(99), sqlBacking.get(PLAYER_ID));
    }
}
