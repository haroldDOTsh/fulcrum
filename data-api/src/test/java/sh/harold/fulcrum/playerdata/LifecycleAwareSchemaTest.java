package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.LifecycleAwareSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LifecycleAwareSchemaTest {
    static final List<String> called = new ArrayList<>();
    static final UUID PLAYER_ID = UUID.randomUUID();

    @BeforeEach
    void reset() {
        called.clear();
        PlayerDataRegistry.clear();
    }

    @Test
    void lifecycleHooksAreCalled() {
        var schema = new TestSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        PlayerDataRegistry.notifyJoin(PLAYER_ID);
        PlayerDataRegistry.notifyQuit(PLAYER_ID);
        assertEquals(List.of("JOIN:" + PLAYER_ID, "QUIT:" + PLAYER_ID), called);
    }

    record TestData(String value) {
    }

    static class TestSchema extends JsonSchema<TestData> implements LifecycleAwareSchema {
        @Override
        public String schemaKey() {
            return "test";
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

        @Override
        public void onJoin(UUID playerId) {
            called.add("JOIN:" + playerId);
        }

        @Override
        public void onQuit(UUID playerId) {
            called.add("QUIT:" + playerId);
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
    }
}
