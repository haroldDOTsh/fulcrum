package sh.harold.fulcrum.api.data.backend.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;

import java.util.UUID;

/**
 * MongoDB backend for persistent player data storage. Suitable for use with JsonSchema<T>.
 */
public class MongoDataBackend implements PlayerDataBackend {
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
}
