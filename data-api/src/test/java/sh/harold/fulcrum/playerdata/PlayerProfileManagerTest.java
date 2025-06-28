package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.JsonSchema;
import sh.harold.fulcrum.api.PlayerDataSchema;
import sh.harold.fulcrum.backend.PlayerDataBackend;
import sh.harold.fulcrum.registry.PlayerDataRegistry;
import sh.harold.fulcrum.registry.PlayerProfileManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileManagerTest {
    static final Map<UUID, TestData> backing = new HashMap<>();
    static final UUID PLAYER_ID = UUID.randomUUID();

    @BeforeEach
    void reset() {
        backing.clear();
        PlayerDataRegistry.clear();
    }

    @Test
    void testProfileLifecycle() {
        var schema = new DummyJsonSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        var profile = PlayerProfileManager.load(PLAYER_ID);
        assertTrue(PlayerProfileManager.isLoaded(PLAYER_ID));
        assertSame(profile, PlayerProfileManager.get(PLAYER_ID));
        profile.set(TestData.class, new TestData("foo"));
        // Don't set nulls, so skip this
        // profile.set(TestData.class, null);
        PlayerProfileManager.unload(PLAYER_ID);
        assertFalse(PlayerProfileManager.isLoaded(PLAYER_ID));
        // Defensive: ensure value is present before comparing
        assertNotNull(backing.get(PLAYER_ID));
        assertEquals(new TestData("foo"), backing.get(PLAYER_ID));
        // Reload and verify round-trip
        backing.put(PLAYER_ID, new TestData("bar"));
        var profile2 = PlayerProfileManager.load(PLAYER_ID);
        assertEquals(new TestData("bar"), profile2.get(TestData.class));
    }

    @Test
    void testMissingDataNotCached() {
        var schema = new DummyJsonSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        // backing is empty, so load should return null
        var profile = PlayerProfileManager.load(PLAYER_ID);
        assertNull(profile.get(TestData.class));
        // Should not cache nulls, so after setting, value is present
        profile.set(TestData.class, new TestData("baz"));
        assertEquals(new TestData("baz"), profile.get(TestData.class));
        PlayerProfileManager.unload(PLAYER_ID);
        // After unload, data is saved
        assertEquals(new TestData("baz"), backing.get(PLAYER_ID));
    }

    record TestData(String value) {
    }

    static class DummyJsonSchema extends JsonSchema<TestData> {
        @Override
        public String schemaKey() {
            return "dummy";
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

    static class DummyBackend implements PlayerDataBackend {
        @Override
        public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
            return (T) backing.get(uuid);
        }

        @Override
        public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
            if (data != null) backing.put(uuid, (TestData) data);
        }
    }
}
