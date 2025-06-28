package sh.harold.fulcrum.playerdata;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.backend.core.PluginJsonData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginJsonDataTest {
    @Test
    void roundTripPreservesNestedStructure() {
        String raw = "{" +
                "\"a\":1," +
                "\"nested\":{\"x\":true,\"y\":\"hello\"}" +
                "}";
        var data = PluginJsonData.fromJsonString(raw);
        String out = data.toJsonString();
        var reparsed = PluginJsonData.fromJsonString(out);
        assertEquals(1, reparsed.json().get("a").getAsInt());
        assertTrue(reparsed.json().getAsJsonObject("nested").get("x").getAsBoolean());
        assertEquals("hello", reparsed.json().getAsJsonObject("nested").get("y").getAsString());
    }

    @Test
    void canAccessAndModifySubfields() {
        String raw = "{\"a\":1,\"nested\":{\"x\":true}}";
        var data = PluginJsonData.fromJsonString(raw);
        data.json().getAsJsonObject("nested").addProperty("y", "world");
        assertEquals("world", data.json().getAsJsonObject("nested").get("y").getAsString());
    }
}
