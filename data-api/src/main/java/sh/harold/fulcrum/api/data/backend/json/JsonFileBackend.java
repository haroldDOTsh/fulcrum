package sh.harold.fulcrum.api.data.backend.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * JSON file backend for player data using Jackson.
 */
public class JsonFileBackend implements PlayerDataBackend {
    private static final Logger LOGGER = Logger.getLogger(JsonFileBackend.class.getName());
    private final File baseDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileBackend(String basePath) {
        this.baseDir = new File(basePath);
        this.baseDir.mkdirs();
    }

    @Override
    public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
        if (!(schema instanceof JsonSchema<T> jsonSchema))
            throw new IllegalArgumentException("Not a JsonSchema: " + schema.type());
        try {
            File dir = new File(baseDir, jsonSchema.schemaKey());
            File file = new File(dir, uuid + ".json");
            if (!file.exists()) return null;
            var tree = mapper.readTree(file);
            String json = mapper.writeValueAsString(tree);
            return jsonSchema.deserialize(uuid, json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load JSON for " + schema.schemaKey() + ": " + e, e);
        }
    }

    @Override
    public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
        if (!(schema instanceof JsonSchema<T> jsonSchema))
            throw new IllegalArgumentException("Not a JsonSchema: " + schema.type());
        try {
            File dir = new File(baseDir, jsonSchema.schemaKey());
            dir.mkdirs();
            File file = new File(dir, uuid + ".json");
            String json = jsonSchema.serialize(uuid, data);
            var node = mapper.readTree(json);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, node);
            LOGGER.info("Saved JSON data for " + uuid + " to " + file.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to save JSON for " + schema.schemaKey() + ": " + e, e);
        }
    }

    @Override
    public <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema) {
        if (!(schema instanceof JsonSchema<T> jsonSchema)) {
            throw new IllegalArgumentException("Not a JsonSchema: " + schema.type());
        }

        T loadedData = load(uuid, schema);
        if (loadedData != null) {
            return loadedData;
        }

        T newInstance = jsonSchema.deserialize(uuid, "{}");
        save(uuid, schema, newInstance);
        return newInstance;
    }
}

