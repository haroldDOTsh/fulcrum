package sh.harold.fulcrum.api.data.impl.mongodb;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import sh.harold.fulcrum.api.data.impl.DocumentImpl;
import sh.harold.fulcrum.api.data.impl.QueryImpl;
import sh.harold.fulcrum.api.data.query.Query;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of StorageBackend.
 * Provides full document operations with MongoDB native features.
 */
public class MongoStorageBackend implements StorageBackend {

    private static final String ID_FIELD = "_id";
    private final MongoConnectionAdapter connectionAdapter;
    private final MongoDatabase database;
    private final MongoClient mongoClient;

    public MongoStorageBackend(MongoConnectionAdapter connectionAdapter) {
        this.connectionAdapter = connectionAdapter;
        this.database = connectionAdapter.getMongoDatabase();
        this.mongoClient = connectionAdapter.getMongoClient();
    }

    @Override
    public CompletableFuture<sh.harold.fulcrum.api.data.Document> getDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);
            Document mongoDoc = mongoCollection.find(Filters.eq(ID_FIELD, id)).first();

            Map<String, Object> data = null;
            if (mongoDoc != null) {
                data = documentToMap(mongoDoc);
            }

            return new DocumentImpl(collection, id, data, this);
        });
    }

    @Override
    public CompletableFuture<Void> saveDocument(String collection, String id, Map<String, Object> data) {
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            Document mongoDoc = mapToDocument(data);
            mongoDoc.put(ID_FIELD, id);

            ReplaceOptions options = new ReplaceOptions().upsert(true);
            mongoCollection.replaceOne(Filters.eq(ID_FIELD, id), mongoDoc, options);
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteDocument(String collection, String id) {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);
            DeleteResult result = mongoCollection.deleteOne(Filters.eq(ID_FIELD, id));
            return result.getDeletedCount() > 0;
        });
    }

    @Override
    public CompletableFuture<List<sh.harold.fulcrum.api.data.Document>> query(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            FindIterable<Document> find;
            if (query instanceof QueryImpl queryImpl) {
                Bson filter = buildMongoFilter(queryImpl);
                find = mongoCollection.find(filter);

                // Apply sorting
                Bson sort = buildSort(queryImpl);
                if (sort != null) {
                    find = find.sort(sort);
                }

                // Apply pagination
                Integer skip = getSkip(queryImpl);
                if (skip != null && skip > 0) {
                    find = find.skip(skip);
                }

                Integer limit = getLimit(queryImpl);
                if (limit != null && limit > 0) {
                    find = find.limit(limit);
                }
            } else {
                find = mongoCollection.find();
            }

            List<sh.harold.fulcrum.api.data.Document> results = new ArrayList<>();
            try (MongoCursor<Document> cursor = find.iterator()) {
                while (cursor.hasNext()) {
                    Document mongoDoc = cursor.next();
                    String docId = mongoDoc.getString(ID_FIELD);
                    Map<String, Object> data = documentToMap(mongoDoc);
                    results.add(new DocumentImpl(collection, docId, data, this));
                }
            }

            return results;
        });
    }

    @Override
    public CompletableFuture<Long> count(String collection, Query query) {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            if (query == null) {
                return mongoCollection.countDocuments();
            }

            if (query instanceof QueryImpl queryImpl) {
                Bson filter = buildMongoFilter(queryImpl);
                return mongoCollection.countDocuments(filter);
            }

            return mongoCollection.countDocuments();
        });
    }

    @Override
    public CompletableFuture<List<sh.harold.fulcrum.api.data.Document>> getAllDocuments(String collection) {
        return CompletableFuture.supplyAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            List<sh.harold.fulcrum.api.data.Document> results = new ArrayList<>();
            try (MongoCursor<Document> cursor = mongoCollection.find().iterator()) {
                while (cursor.hasNext()) {
                    Document mongoDoc = cursor.next();
                    String docId = mongoDoc.getString(ID_FIELD);
                    Map<String, Object> data = documentToMap(mongoDoc);
                    results.add(new DocumentImpl(collection, docId, data, this));
                }
            }

            return results;
        });
    }

    /**
     * Update a specific field in a document.
     */
    public CompletableFuture<Void> updateField(String collection, String id, String path, Object value) {
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            // Convert path to MongoDB dot notation
            String mongoPath = convertToMongoPath(path);

            Bson filter = Filters.eq(ID_FIELD, id);
            Bson update = Updates.set(mongoPath, value);

            UpdateOptions options = new UpdateOptions().upsert(false);
            mongoCollection.updateOne(filter, update, options);
        });
    }

    /**
     * Increment a numeric field atomically.
     */
    public CompletableFuture<Void> incrementField(String collection, String id, String path, Number amount) {
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            String mongoPath = convertToMongoPath(path);

            Bson filter = Filters.eq(ID_FIELD, id);
            Bson update = Updates.inc(mongoPath, amount);

            UpdateOptions options = new UpdateOptions().upsert(false);
            mongoCollection.updateOne(filter, update, options);
        });
    }

    /**
     * Push a value to an array field.
     */
    public CompletableFuture<Void> pushToArray(String collection, String id, String path, Object value) {
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            String mongoPath = convertToMongoPath(path);

            Bson filter = Filters.eq(ID_FIELD, id);
            Bson update = Updates.push(mongoPath, value);

            UpdateOptions options = new UpdateOptions().upsert(false);
            mongoCollection.updateOne(filter, update, options);
        });
    }

    /**
     * Pull a value from an array field.
     */
    public CompletableFuture<Void> pullFromArray(String collection, String id, String path, Object value) {
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> mongoCollection = database.getCollection(collection);

            String mongoPath = convertToMongoPath(path);

            Bson filter = Filters.eq(ID_FIELD, id);
            Bson update = Updates.pull(mongoPath, value);

            UpdateOptions options = new UpdateOptions().upsert(false);
            mongoCollection.updateOne(filter, update, options);
        });
    }

    /**
     * Execute operations within a transaction.
     */
    public <T> CompletableFuture<T> executeInTransaction(Function<ClientSession, T> operations) {
        return CompletableFuture.supplyAsync(() -> {
            try (ClientSession session = mongoClient.startSession()) {
                session.startTransaction();
                try {
                    T result = operations.apply(session);
                    session.commitTransaction();
                    return result;
                } catch (Exception e) {
                    session.abortTransaction();
                    throw new RuntimeException("Transaction failed", e);
                }
            }
        });
    }

    /**
     * Build MongoDB filter from QueryImpl conditions.
     */
    private Bson buildMongoFilter(QueryImpl queryImpl) {
        List<QueryImpl.Condition> conditions = queryImpl.getConditions();

        if (conditions.isEmpty()) {
            return new Document();
        }

        // For a single condition, return it directly
        if (conditions.size() == 1) {
            return createFilterForCondition(conditions.get(0));
        }

        List<Bson> currentGroup = new ArrayList<>();
        List<Bson> orGroups = new ArrayList<>();
        QueryImpl.LogicalOperator lastOp = QueryImpl.LogicalOperator.AND;

        for (QueryImpl.Condition condition : conditions) {
            Bson filter = createFilterForCondition(condition);

            if (condition.operator() == QueryImpl.LogicalOperator.OR && !currentGroup.isEmpty()) {
                // End current AND group and start OR
                if (currentGroup.size() == 1) {
                    orGroups.add(currentGroup.get(0));
                } else {
                    orGroups.add(Filters.and(currentGroup));
                }
                currentGroup = new ArrayList<>();
            }

            currentGroup.add(filter);
            lastOp = condition.operator();
        }

        // Handle the last group
        if (!currentGroup.isEmpty()) {
            if (lastOp == QueryImpl.LogicalOperator.OR || !orGroups.isEmpty()) {
                if (currentGroup.size() == 1) {
                    orGroups.add(currentGroup.get(0));
                } else {
                    orGroups.add(Filters.and(currentGroup));
                }
            }
        }

        // Build final filter
        if (!orGroups.isEmpty()) {
            return orGroups.size() == 1 ? orGroups.get(0) : Filters.or(orGroups);
        } else if (!currentGroup.isEmpty()) {
            return currentGroup.size() == 1 ? currentGroup.get(0) : Filters.and(currentGroup);
        }

        return new Document();
    }

    /**
     * Create a MongoDB filter for a single condition.
     */
    private Bson createFilterForCondition(QueryImpl.Condition condition) {
        String fieldPath = convertToMongoPath(condition.path());

        switch (condition.type()) {
            case EQUAL:
                return Filters.eq(fieldPath, condition.value());

            case NOT_EQUAL:
                return Filters.ne(fieldPath, condition.value());

            case CONTAINS:
                if (condition.value() instanceof String) {
                    return Filters.regex(fieldPath, (String) condition.value());
                } else {
                    return Filters.in(fieldPath, Collections.singletonList(condition.value()));
                }

            case GREATER_THAN:
                return Filters.gt(fieldPath, condition.value());

            case LESS_THAN:
                return Filters.lt(fieldPath, condition.value());

            case IN:
                if (condition.value() instanceof List) {
                    return Filters.in(fieldPath, (List<?>) condition.value());
                }
                return Filters.eq(fieldPath, condition.value());

            default:
                return new Document();
        }
    }

    /**
     * Build MongoDB sort document from QueryImpl.
     */
    private Bson buildSort(QueryImpl queryImpl) {
        try {
            // Use reflection to access private fields
            java.lang.reflect.Field sortFieldField = QueryImpl.class.getDeclaredField("sortField");
            sortFieldField.setAccessible(true);
            String sortField = (String) sortFieldField.get(queryImpl);

            if (sortField == null) {
                return null;
            }

            java.lang.reflect.Field sortAscendingField = QueryImpl.class.getDeclaredField("sortAscending");
            sortAscendingField.setAccessible(true);
            boolean sortAscending = (boolean) sortAscendingField.get(queryImpl);

            return sortAscending ? Sorts.ascending(sortField) : Sorts.descending(sortField);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get skip value from QueryImpl.
     */
    private Integer getSkip(QueryImpl queryImpl) {
        try {
            java.lang.reflect.Field skipField = QueryImpl.class.getDeclaredField("skipValue");
            skipField.setAccessible(true);
            return (Integer) skipField.get(queryImpl);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get limit value from QueryImpl.
     */
    private Integer getLimit(QueryImpl queryImpl) {
        try {
            java.lang.reflect.Field limitField = QueryImpl.class.getDeclaredField("limitValue");
            limitField.setAccessible(true);
            return (Integer) limitField.get(queryImpl);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert Java Map to MongoDB Document.
     */
    private Document mapToDocument(Map<String, Object> map) {
        Document doc = new Document();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map) {
                doc.put(entry.getKey(), mapToDocument((Map<String, Object>) entry.getValue()));
            } else {
                doc.put(entry.getKey(), entry.getValue());
            }
        }
        return doc;
    }

    /**
     * Convert MongoDB Document to Java Map.
     * Handles type conversions for MongoDB numeric types.
     */
    private Map<String, Object> documentToMap(Document doc) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            if (!ID_FIELD.equals(entry.getKey())) {
                Object value = entry.getValue();
                if (value instanceof Document) {
                    map.put(entry.getKey(), documentToMap((Document) value));
                } else if (value instanceof List) {
                    map.put(entry.getKey(), convertList((List<?>) value));
                } else if (value instanceof Integer) {
                    // Convert Integer to Long for consistency
                    map.put(entry.getKey(), ((Integer) value).longValue());
                } else {
                    map.put(entry.getKey(), value);
                }
            }
        }
        return map;
    }

    /**
     * Convert a list that might contain Documents.
     */
    private List<Object> convertList(List<?> list) {
        return list.stream()
                .map(item -> {
                    if (item instanceof Document) {
                        return documentToMap((Document) item);
                    } else {
                        return item;
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Convert path notation to MongoDB dot notation.
     */
    private String convertToMongoPath(String path) {
        // Path is already in dot notation
        return path;
    }

    /**
     * Close the MongoDB connection.
     */
    public void close() {
        if (connectionAdapter != null) {
            connectionAdapter.close();
        }
    }
}