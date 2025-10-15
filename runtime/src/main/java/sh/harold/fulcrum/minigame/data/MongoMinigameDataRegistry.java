package sh.harold.fulcrum.minigame.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Mongo-backed implementation of the minigame data registry.
 */
public final class MongoMinigameDataRegistry implements MinigameDataRegistry {

    private final MongoDatabase database;
    private final ObjectMapper mapper;
    private final Logger logger;
    private final ConcurrentHashMap<String, CollectionHolder> collections = new ConcurrentHashMap<>();

    public MongoMinigameDataRegistry(MongoDatabase database, ObjectMapper mapper, Logger logger) {
        this.database = Objects.requireNonNull(database, "database");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.logger = logger != null ? logger : Logger.getLogger(MongoMinigameDataRegistry.class.getName());
    }

    @Override
    public <T> MinigameCollection<T> register(String familyId, String collectionName, Class<T> pojoClass) {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(collectionName, "collectionName");
        Objects.requireNonNull(pojoClass, "pojoClass");

        String key = familyId.toLowerCase(Locale.ROOT);
        CollectionHolder holder = collections.compute(key, (ignored, existing) -> {
            if (existing != null) {
                if (!existing.pojoClass().equals(pojoClass)) {
                    logger.warning("Minigame collection for family '" + familyId + "' already registered with " + existing.pojoClass().getName() + "; ignoring request for " + pojoClass.getName());
                }
                return existing;
            }
            var mongoCollection = database.getCollection(collectionName, Document.class);
            Supplier<T> factory = createFactory(pojoClass);
            logger.info(() -> "Registered minigame collection '" + collectionName + "' for family " + familyId);
            MinigameCollection<T> collection = new MongoMinigameCollection<>(mongoCollection, pojoClass, factory, mapper, logger);
            return new CollectionHolder(collection, pojoClass);
        });
        @SuppressWarnings("unchecked")
        MinigameCollection<T> typed = (MinigameCollection<T>) holder.collection();
        return typed;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<MinigameCollection<T>> get(String familyId, Class<T> pojoClass) {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(pojoClass, "pojoClass");
        CollectionHolder holder = collections.get(familyId.toLowerCase(Locale.ROOT));
        if (holder == null || !holder.pojoClass().equals(pojoClass)) {
            return Optional.empty();
        }
        return Optional.of((MinigameCollection<T>) holder.collection());
    }

    @Override
    public MinigameCollection<Map<String, Object>> registerDefault(String familyId) {
        return register(familyId, defaultCollectionName(familyId), uncheckedMapClass());
    }

    private String defaultCollectionName(String familyId) {
        return "player_data_" + familyId.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Class<Map<String, Object>> uncheckedMapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private <T> Supplier<T> createFactory(Class<T> pojoClass) {
        if (Map.class.equals(pojoClass)) {
            return () -> pojoClass.cast(new ConcurrentHashMap<String, Object>());
        }
        return () -> {
            try {
                return pojoClass.getDeclaredConstructor().newInstance();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to instantiate " + pojoClass.getName(), exception);
            }
        };
    }

    private record CollectionHolder(MinigameCollection<?> collection, Class<?> pojoClass) {
    }
}
