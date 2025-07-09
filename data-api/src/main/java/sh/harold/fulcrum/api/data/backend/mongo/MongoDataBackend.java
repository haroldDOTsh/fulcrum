package sh.harold.fulcrum.api.data.backend.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.model.ReplaceOneModel;
import org.bson.Document;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MongoDB backend for persistent player data storage with batch operation support.
 * Suitable for use with JsonSchema<T>.
 */
public class MongoDataBackend implements PlayerDataBackend {
    private static final Logger LOGGER = Logger.getLogger(MongoDataBackend.class.getName());
    private final MongoCollection<Document> collection;

    public MongoDataBackend(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    @Override
    public <T> T load(UUID uuid, PlayerDataSchema<T> schema) {
        Document doc = collection.find(Filters.eq("_id", uuid.toString())).first();
        if (doc == null) return null;
        doc.remove("_id");
        if (schema instanceof JsonSchema<T> jsonSchema) {
            return jsonSchema.deserialize(uuid, doc.toJson());
        }
        throw new UnsupportedOperationException("MongoDataBackend only supports JsonSchema<T>");
    }

    @Override
    public <T> void save(UUID uuid, PlayerDataSchema<T> schema, T data) {
        if (schema instanceof JsonSchema<T> jsonSchema) {
            String json = jsonSchema.serialize(uuid, data);
            Document doc = Document.parse(json);
            doc.put("_id", uuid.toString());
            collection.replaceOne(Filters.eq("_id", uuid.toString()), doc, new ReplaceOptions().upsert(true));
            return;
        }
        throw new UnsupportedOperationException("MongoDataBackend only supports JsonSchema<T>");
    }

    @Override
    public <T> T loadOrCreate(UUID uuid, PlayerDataSchema<T> schema) {
        if (!(schema instanceof JsonSchema<T> jsonSchema)) {
            throw new UnsupportedOperationException("MongoDataBackend only supports JsonSchema<T>");
        }

        Document doc = collection.find(Filters.eq("_id", uuid.toString())).first();

        if (doc != null) {
            doc.remove("_id");
            return jsonSchema.deserialize(uuid, doc.toJson());
        }

        T newInstance = jsonSchema.deserialize(uuid, "{}");
        save(uuid, schema, newInstance);
        return newInstance;
    }
    
    @Override
    public int saveBatch(Map<UUID, Map<PlayerDataSchema<?>, Object>> entries) {
        List<WriteModel<Document>> writeModels = new ArrayList<>();
        int totalEntries = 0;
        
        for (Map.Entry<UUID, Map<PlayerDataSchema<?>, Object>> playerEntry : entries.entrySet()) {
            UUID playerId = playerEntry.getKey();
            
            for (Map.Entry<PlayerDataSchema<?>, Object> schemaEntry : playerEntry.getValue().entrySet()) {
                try {
                    PlayerDataSchema<?> schema = schemaEntry.getKey();
                    Object data = schemaEntry.getValue();
                    
                    if (!(schema instanceof JsonSchema)) {
                        LOGGER.log(Level.WARNING, "Skipping non-JsonSchema entry for player {0}: {1}",
                                 new Object[]{playerId, schema.getClass().getSimpleName()});
                        continue;
                    }
                    
                    @SuppressWarnings("unchecked")
                    JsonSchema<Object> jsonSchema = (JsonSchema<Object>) schema;
                    String json = jsonSchema.serialize(playerId, data);
                    Document doc = Document.parse(json);
                    doc.put("_id", playerId.toString());
                    
                    writeModels.add(new ReplaceOneModel<>(
                        Filters.eq("_id", playerId.toString()),
                        doc,
                        new ReplaceOptions().upsert(true)
                    ));
                    
                    totalEntries++;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to prepare batch entry for player " + playerId +
                             ", schema " + schemaEntry.getKey().schemaKey(), e);
                }
            }
        }
        
        if (writeModels.isEmpty()) {
            return 0;
        }
        
        try {
            collection.bulkWrite(writeModels);
            LOGGER.log(Level.INFO, "Batch saved {0} entries to MongoDB", totalEntries);
            return totalEntries;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to execute batch write to MongoDB", e);
            return 0;
        }
    }
    
    @Override
    public <T> boolean saveChangedFields(UUID uuid, PlayerDataSchema<T> schema, T data, Collection<String> changedFields) {
        // For MongoDB, we don't have field-level granularity in this implementation,
        // so we save the entire document
        try {
            save(uuid, schema, data);
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save changed fields for player " + uuid +
                     ", schema " + schema.schemaKey(), e);
            return false;
        }
    }
}
