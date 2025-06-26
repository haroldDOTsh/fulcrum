package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStorageManagerTest {
    record TestData(String value) {}
    static final Map<UUID, TestData> jsonMap = new HashMap<>();
    static final Map<UUID, TestData> sqlMap = new HashMap<>();
    static final UUID PLAYER_ID = UUID.randomUUID();

    static class DummyJsonSchema extends JsonSchema<TestData> {
        @Override public String schemaKey() { return "json"; }
        @Override public Class<TestData> type() { return TestData.class; }
        @Override public TestData load(UUID uuid) { return jsonMap.get(uuid); }
        @Override public void save(UUID uuid, TestData data) { jsonMap.put(uuid, data); }
        @Override public TestData deserialize(UUID uuid, String json) { return null; }
        @Override public String serialize(UUID uuid, TestData data) { return null; }
    }
    static class DummySqlSchema extends TableSchema<TestData> {
        @Override public String schemaKey() { return "sql"; }
        @Override public Class<TestData> type() { return TestData.class; }
        @Override public TestData load(UUID uuid) { return sqlMap.get(uuid); }
        @Override public void save(UUID uuid, TestData data) { sqlMap.put(uuid, data); }
    }

    @BeforeEach
    void reset() {
        jsonMap.clear();
        sqlMap.clear();
    }

    @Test
    void testLoadAndSave() {
        var jsonSchema = new DummyJsonSchema();
        var sqlSchema = new DummySqlSchema();
        var data1 = new TestData("foo");
        var data2 = new TestData("bar");
        PlayerStorageManager.save(PLAYER_ID, jsonSchema, data1);
        PlayerStorageManager.save(PLAYER_ID, sqlSchema, data2);
        assertEquals(data1, PlayerStorageManager.load(PLAYER_ID, jsonSchema));
        assertEquals(data2, PlayerStorageManager.load(PLAYER_ID, sqlSchema));
    }
}
