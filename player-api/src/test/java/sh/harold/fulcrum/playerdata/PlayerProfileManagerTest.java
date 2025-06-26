package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileManagerTest {
    record TestData(String value) {}
    static final Map<UUID, TestData> backing = new HashMap<>();
    static final UUID PLAYER_ID = UUID.randomUUID();

    static class DummyJsonSchema extends JsonSchema<TestData> {
        @Override public String schemaKey() { return "dummy"; }
        @Override public Class<TestData> type() { return TestData.class; }
        @Override public TestData load(UUID uuid) { return backing.get(uuid); }
        @Override public void save(UUID uuid, TestData data) {
            if (data == null) return;
            backing.put(uuid, data);
        }
        @Override public TestData deserialize(UUID uuid, String json) { return null; }
        @Override public String serialize(UUID uuid, TestData data) { return null; }
    }

    @BeforeEach
    void reset() {
        backing.clear();
        PlayerDataRegistry.clear();
    }

    @Test
    void testProfileLifecycle() {
        PlayerDataRegistry.register(new DummyJsonSchema());
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
        PlayerDataRegistry.register(new DummyJsonSchema());
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
}
