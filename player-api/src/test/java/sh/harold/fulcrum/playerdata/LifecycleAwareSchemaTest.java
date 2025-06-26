package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleAwareSchemaTest {
    record TestData(String value) {}
    static final List<String> called = new ArrayList<>();
    static final UUID PLAYER_ID = UUID.randomUUID();

    static class TestSchema extends JsonSchema<TestData> implements LifecycleAwareSchema {
        @Override public String schemaKey() { return "test"; }
        @Override public Class<TestData> type() { return TestData.class; }
        @Override public TestData load(UUID uuid) { return null; }
        @Override public void save(UUID uuid, TestData data) {}
        @Override public TestData deserialize(UUID uuid, String json) { return null; }
        @Override public String serialize(UUID uuid, TestData data) { return null; }
        @Override public void onJoin(UUID playerId) { called.add("JOIN:" + playerId); }
        @Override public void onQuit(UUID playerId) { called.add("QUIT:" + playerId); }
    }

    @BeforeEach
    void reset() {
        called.clear();
        PlayerDataRegistry.clear();
    }

    @Test
    void lifecycleHooksAreCalled() {
        var schema = new TestSchema();
        PlayerDataRegistry.register(schema);
        PlayerDataRegistry.notifyJoin(PLAYER_ID);
        PlayerDataRegistry.notifyQuit(PLAYER_ID);
        assertEquals(List.of("JOIN:" + PLAYER_ID, "QUIT:" + PLAYER_ID), called);
    }
}
