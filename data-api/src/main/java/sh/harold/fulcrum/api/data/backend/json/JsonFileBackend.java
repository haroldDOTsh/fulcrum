package sh.harold.fulcrum.api.data.backend.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON file backend for player data using Jackson with batch operation support.
 */
public class JsonFileBackend implements PlayerDataBackend {
    private static final Logger LOGGER = Logger.getLogger(JsonFileBackend.class.getName());
    private final File baseDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService batchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "JsonFileBackend-Batch");
        t.setDaemon(true);
        return t;
    });

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
    
    @Override
    public int saveBatch(Map<UUID, Map<PlayerDataSchema<?>, Object>> entries) {
        int savedCount = 0;
        
        // Process entries in parallel for better performance
        CompletableFuture<Integer>[] futures = entries.entrySet().stream()
            .map(playerEntry -> CompletableFuture.supplyAsync(() -> {
                UUID playerId = playerEntry.getKey();
                int playerSavedCount = 0;
                
                for (Map.Entry<PlayerDataSchema<?>, Object> schemaEntry : playerEntry.getValue().entrySet()) {
                    try {
                        @SuppressWarnings("unchecked")
                        PlayerDataSchema<Object> schema = (PlayerDataSchema<Object>) schemaEntry.getKey();
                        save(playerId, schema, schemaEntry.getValue());
                        playerSavedCount++;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to save batch entry for player " + playerId +
                                 ", schema " + schemaEntry.getKey().schemaKey(), e);
                    }
                }
                return playerSavedCount;
            }, batchExecutor))
            .toArray(CompletableFuture[]::new);
        
        // Wait for all saves to complete and sum the results
        try {
            CompletableFuture.allOf(futures).join();
            for (CompletableFuture<Integer> future : futures) {
                savedCount += future.get();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during batch save operation", e);
        }
        
        if (savedCount > 0) {
            LOGGER.log(Level.INFO, "Batch saved {0} entries to JSON files", savedCount);
        }
        
        return savedCount;
    }
    
    @Override
    public <T> boolean saveChangedFields(UUID uuid, PlayerDataSchema<T> schema, T data, Collection<String> changedFields) {
        // For JSON files, we don't have field-level granularity, so we save the entire object
        try {
            save(uuid, schema, data);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save changed fields for player " + uuid +
                     ", schema " + schema.schemaKey(), e);
            return false;
        }
    }
    
    /**
     * Shuts down the batch executor and cleans up resources.
     */
    public void shutdown() {
        batchExecutor.shutdown();
        try {
            if (!batchExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

