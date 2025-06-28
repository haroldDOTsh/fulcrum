package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;
import sh.harold.fulcrum.api.data.registry.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileAsyncTest {
    static final Map<UUID, TestStats> backing = new ConcurrentHashMap<>();
    static final UUID PLAYER_ID = UUID.randomUUID();

    @BeforeEach
    void reset() {
        backing.clear();
        PlayerDataRegistry.clear();
    }

    @Test
    void getAsyncLoadsAndCaches() {
        var schema = new TestStatsSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        backing.put(PLAYER_ID, new TestStats(5));
        var profile = new PlayerProfile(PLAYER_ID);
        var future = profile.getAsync(TestStats.class);
        assertEquals(5, future.join().level());
        // Should be cached now
        backing.put(PLAYER_ID, new TestStats(99));
        var future2 = profile.getAsync(TestStats.class);
        assertEquals(5, future2.join().level());
    }

    @Test
    void saveAsyncPersistsAndCaches() {
        var schema = new TestStatsSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        var profile = new PlayerProfile(PLAYER_ID);
        var stats = new TestStats(42);
        profile.saveAsync(TestStats.class, stats).join();
        assertEquals(stats, backing.get(PLAYER_ID));
        // Should be cached
        assertEquals(stats, profile.getAsync(TestStats.class).join());
    }

    @Test
    void getAsyncThrowsIfSchemaMissing() {
        var profile = new PlayerProfile(PLAYER_ID);
        var ex = assertThrows(CompletionException.class, () -> profile.getAsync(TestStats.class).join());
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void saveAsyncThrowsIfSchemaMissing() {
        var profile = new PlayerProfile(PLAYER_ID);
        var ex = assertThrows(CompletionException.class, () -> profile.saveAsync(TestStats.class, new TestStats(1)).join());
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void saveAsyncThrowsIfTypeMismatch() {
        var schema = new TestStatsSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        var profile = new PlayerProfile(PLAYER_ID);
        // Use raw type to bypass compile-time check
        @SuppressWarnings("rawtypes")
        PlayerProfile rawProfile = (PlayerProfile) profile;
        var ex = assertThrows(CompletionException.class, () -> rawProfile.saveAsync((Class) TestStats.class, (Object) "not stats").join());
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void loadAllAsyncLoadsAllSchemas() {
        var schema = new TestStatsSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        backing.put(PLAYER_ID, new TestStats(7));
        var profile = new PlayerProfile(PLAYER_ID);
        profile.loadAllAsync().join();
        assertEquals(7, profile.get(TestStats.class).level());
    }

    @Test
    void saveAllAsyncPersistsAllSchemas() {
        var schema = new TestStatsSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        var profile = new PlayerProfile(PLAYER_ID);
        profile.set(TestStats.class, new TestStats(88));
        profile.saveAllAsync().join();
        assertEquals(new TestStats(88), backing.get(PLAYER_ID));
    }

    @Test
    void saveAllAsyncSkipsNulls() {
        var schema = new TestStatsSchema();
        var backend = new DummyBackend();
        PlayerDataRegistry.registerSchema(schema, backend);
        var profile = new PlayerProfile(PLAYER_ID);
        // Do not set nulls; just don't set at all
        profile.saveAllAsync().join();
        assertNull(backing.get(PLAYER_ID));
    }

    record TestStats(int level) {
    }

    static class TestStatsSchema extends JsonSchema<TestStats> {
        @Override
        public String schemaKey() {
            return "stats";
        }

        @Override
        public Class<TestStats> type() {
            return TestStats.class;
        }

        @Override
        public TestStats deserialize(UUID uuid, String json) {
            return null;
        }

        @Override
        public String serialize(UUID uuid, TestStats data) {
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
            backing.put(uuid, (TestStats) data);
        }
    }
}
