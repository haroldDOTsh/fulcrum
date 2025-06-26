package sh.harold.fulcrum.playerdata;

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

    @Test
    void registryStoresAndRetrievesValues() {
        var registry = new PlayerDataRegistry();
        var schema = new DummyJsonSchema();
        registry.register(schema);
        var uuid = UUID.randomUUID();
        var data = new TestData(42);
        System.out.println("Registering schema: " + schema.schemaKey());
        System.out.println("Setting data for UUID: " + uuid + ", value: " + data);
        registry.set(uuid, TestData.class, data);
        var retrieved = registry.get(uuid, TestData.class);
        System.out.println("Retrieved data for UUID: " + uuid + ", value: " + retrieved);
        assertNotNull(retrieved);
        assertEquals(42, retrieved.x());
    }
}
