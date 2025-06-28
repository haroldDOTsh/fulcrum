package sh.harold.fulcrum.backend.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class PluginJsonData {
    private static final Gson gson = new Gson();
    private final JsonObject json;

    private PluginJsonData(JsonObject json) {
        this.json = json;
    }

    public static PluginJsonData fromJsonString(String raw) {
        return new PluginJsonData(JsonParser.parseString(raw).getAsJsonObject());
    }

    public String toJsonString() {
        return gson.toJson(json);
    }

    public JsonObject json() {
        return json;
    }
}
