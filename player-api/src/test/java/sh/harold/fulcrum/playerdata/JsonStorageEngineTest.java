package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JsonStorageEngineTest {
    @Test
    void storesAndRetrievesJsonPerUuidAndSchemaKey() {
        var engine = new InMemoryJsonStorageEngine();
        var uuid = UUID.randomUUID();
        System.out.println("Saving JSON for UUID: " + uuid + ", schemaKey: profile, value: {\"x\":1}");
        engine.save(uuid, "profile", "{\"x\":1}");
        var loaded = engine.load(uuid, "profile");
        System.out.println("Loaded JSON for UUID: " + uuid + ", schemaKey: profile, value: " + loaded);
        assertEquals("{\"x\":1}", loaded);
    }

    @Test
    void savingTwiceOverwritesValue() {
        var engine = new InMemoryJsonStorageEngine();
        var uuid = UUID.randomUUID();
        System.out.println("Saving first value for UUID: " + uuid + ", schemaKey: profile, value: one");
        engine.save(uuid, "profile", "one");
        System.out.println("Saving second value for UUID: " + uuid + ", schemaKey: profile, value: two");
        engine.save(uuid, "profile", "two");
        var loaded = engine.load(uuid, "profile");
        System.out.println("Loaded value after overwrite for UUID: " + uuid + ", schemaKey: profile, value: " + loaded);
        assertEquals("two", loaded);
    }

    @Test
    void retrievingNonexistentKeyReturnsNull() {
        var engine = new InMemoryJsonStorageEngine();
        var uuid = UUID.randomUUID();
        System.out.println("Attempting to load nonexistent key for UUID: " + uuid + ", schemaKey: missing");
        var loaded = engine.load(uuid, "missing");
        System.out.println("Loaded value: " + loaded);
        assertNull(loaded);
    }

    @Test
    void multiplePlayersStoreIndependentData() {
        var engine = new InMemoryJsonStorageEngine();
        var uuid1 = UUID.randomUUID();
        var uuid2 = UUID.randomUUID();
        System.out.println("Saving for UUID1: " + uuid1 + ", value: a");
        engine.save(uuid1, "profile", "a");
        System.out.println("Saving for UUID2: " + uuid2 + ", value: b");
        engine.save(uuid2, "profile", "b");
        var loaded1 = engine.load(uuid1, "profile");
        var loaded2 = engine.load(uuid2, "profile");
        System.out.println("Loaded for UUID1: " + loaded1);
        System.out.println("Loaded for UUID2: " + loaded2);
        assertEquals("a", loaded1);
        assertEquals("b", loaded2);
    }
}
