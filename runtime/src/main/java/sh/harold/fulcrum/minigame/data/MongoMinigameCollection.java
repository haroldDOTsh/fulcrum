package sh.harold.fulcrum.minigame.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class MongoMinigameCollection<T> implements MinigameCollection<T> {

    private final MongoCollection<Document> collection;
    private final Class<T> pojoClass;
    private final Supplier<T> factory;
    private final ObjectMapper mapper;
    private final Logger logger;

    MongoMinigameCollection(MongoCollection<Document> collection,
                            Class<T> pojoClass,
                            Supplier<T> factory,
                            ObjectMapper mapper,
                            Logger logger) {
        this.collection = Objects.requireNonNull(collection, "collection");
        this.pojoClass = Objects.requireNonNull(pojoClass, "pojoClass");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.logger = logger != null ? logger : Logger.getLogger(MongoMinigameCollection.class.getName());
    }

    @Override
    public T load(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Document document = collection.find(Filters.eq("_id", playerId.toString())).first();
        if (document == null) {
            return factory.get();
        }
        Document clone = new Document(document);
        clone.remove("_id");
        return mapper.convertValue(clone, pojoClass);
    }

    @Override
    public void upsert(UUID playerId, Consumer<T> mutator) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(mutator, "mutator");
        T current = load(playerId);
        mutator.accept(current);
        Map<String, Object> payload = mapper.convertValue(current, Map.class);
        Document document = new Document(payload != null ? new ConcurrentHashMap<>(payload) : Map.of());
        document.put("_id", playerId.toString());
        try {
            collection.replaceOne(
                    Filters.eq("_id", playerId.toString()),
                    document,
                    new ReplaceOptions().upsert(true)
            );
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to upsert minigame document for " + playerId, exception);
        }
    }

    @Override
    public void delete(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        collection.deleteOne(Filters.eq("_id", playerId.toString()));
    }
}
