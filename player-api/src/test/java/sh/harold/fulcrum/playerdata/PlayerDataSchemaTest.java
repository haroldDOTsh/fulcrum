package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

public class PlayerDataSchemaTest {
    record TestData(int x) {}

    static class DummyJsonSchema extends JsonSchema<TestData> {
        @Override
        public String schemaKey() { return "test"; }
        @Override
        public Class<TestData> type() { return TestData.class; }
        @Override
        public TestData load(UUID uuid) { return null; }
        @Override
        public void save(UUID uuid, TestData data) {}
        @Override
        public TestData deserialize(UUID uuid, String json) { return new TestData(Integer.parseInt(json)); }
        @Override
        public String serialize(UUID uuid, TestData data) { return Integer.toString(data.x()); }
    }

    @BeforeEach
    void resetRegistry() {
        PlayerDataRegistry.clear();
    }

    @Test
    void registryStoresAndRetrievesValues() {
        var schema = new DummyJsonSchema();
        PlayerDataRegistry.register(schema);
        assertSame(schema, PlayerDataRegistry.get(TestData.class));
        assertTrue(PlayerDataRegistry.allSchemas().contains(schema));
    }

    @Test
    void registryClearEmptiesAllSchemas() {
        PlayerDataRegistry.register(new DummyJsonSchema());
        PlayerDataRegistry.clear();
        assertNull(PlayerDataRegistry.get(TestData.class));
        assertTrue(PlayerDataRegistry.allSchemas().isEmpty());
    }

}
