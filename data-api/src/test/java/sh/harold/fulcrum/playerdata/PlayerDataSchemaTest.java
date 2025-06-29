package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlayerDataSchemaTest {
    @BeforeEach
    void resetRegistry() {
        PlayerDataRegistry.clear();
    }

    @Test
    void registryStoresAndRetrievesValues() {
        var schema = new DummyJsonSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        assertTrue(PlayerDataRegistry.allSchemas().contains(schema));
    }

    @Test
    void registryClearEmptiesAllSchemas() {
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(new DummyJsonSchema(), backend);
        PlayerDataRegistry.clear();
        assertTrue(PlayerDataRegistry.allSchemas().isEmpty());
    }

    record TestData(int x) {
    }

    static class DummyJsonSchema extends JsonSchema<TestData> {
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
            return new TestData(Integer.parseInt(json));
        }

        @Override
        public String serialize(UUID uuid, TestData data) {
            return Integer.toString(data.x());
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
