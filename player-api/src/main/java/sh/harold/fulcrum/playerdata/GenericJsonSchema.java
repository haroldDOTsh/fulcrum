package sh.harold.fulcrum.playerdata;

import java.util.UUID;

public class GenericJsonSchema extends JsonSchema<PluginJsonData> {
    private final String schemaKey;

    public GenericJsonSchema(String schemaKey) {
        this.schemaKey = schemaKey;
    }

    @Override
    public String schemaKey() {
        return schemaKey;
    }

    @Override
    public Class<PluginJsonData> type() {
        return PluginJsonData.class;
    }

    @Override
    public PluginJsonData deserialize(UUID uuid, String json) {
        return PluginJsonData.fromJsonString(json);
    }

    @Override
    public String serialize(UUID uuid, PluginJsonData data) {
        return data.toJsonString();
    }

    @Override
    public PluginJsonData load(UUID uuid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void save(UUID uuid, PluginJsonData data) {
        throw new UnsupportedOperationException();
    }
}
