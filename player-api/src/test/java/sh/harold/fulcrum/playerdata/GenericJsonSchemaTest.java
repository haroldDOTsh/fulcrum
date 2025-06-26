package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GenericJsonSchemaTest {
    @Test
    void storesAndLoadsNestedJsonForMultipleSchemas() {
        var fairySouls = new GenericJsonSchema("fairy_souls");
        var questData = new GenericJsonSchema("quest_data");
        var engine = new InMemoryJsonStorageEngine();
        var uuid = UUID.randomUUID();

        var fairyJson = PluginJsonData.fromJsonString("{\"found\":5,\"locations\":[\"hub\",\"park\"]}");
        var questJson = PluginJsonData.fromJsonString("{\"completed\":true,\"progress\":{\"stage\":2}}\n");

        engine.save(uuid, fairySouls.schemaKey(), fairySouls.serialize(uuid, fairyJson));
        engine.save(uuid, questData.schemaKey(), questData.serialize(uuid, questJson));

        var loadedFairy = fairySouls.deserialize(uuid, engine.load(uuid, fairySouls.schemaKey()));
        var loadedQuest = questData.deserialize(uuid, engine.load(uuid, questData.schemaKey()));

        assertEquals(5, loadedFairy.json().get("found").getAsInt());
        assertTrue(loadedQuest.json().get("completed").getAsBoolean());
        assertEquals(2, loadedQuest.json().getAsJsonObject("progress").get("stage").getAsInt());
    }
}
