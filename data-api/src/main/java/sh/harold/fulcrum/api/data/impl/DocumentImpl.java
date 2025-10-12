package sh.harold.fulcrum.api.data.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of the Document interface.
 * Handles document data access and manipulation.
 */
public class DocumentImpl implements Document {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final String collection;
    private final String id;
    private final Map<String, Object> data;
    private final StorageBackend backend;

    public DocumentImpl(String collection, String id, Map<String, Object> data, StorageBackend backend) {
        this.collection = collection;
        this.id = id;
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        this.backend = backend;
    }

    @Override
    public Object get(String path) {
        return get(path, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String path, T defaultValue) {
        String[] parts = path.split("\\.");
        Object current = data;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
                if (current == null) {
                    return defaultValue;
                }
            } else {
                return defaultValue;
            }
        }

        return current != null ? (T) current : defaultValue;
    }

    @Override
    public CompletableFuture<Document> setAsync(String path, Object value) {
        setValueAtPath(path, value);
        Map<String, Object> snapshot = new HashMap<>(data);
        return backend.saveDocument(collection, id, snapshot)
                .thenApply(ignored -> this);
    }

    private void setValueAtPath(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = data;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = current.get(part);
            if (!(next instanceof Map)) {
                next = new HashMap<String, Object>();
                current.put(part, next);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nextMap = (Map<String, Object>) next;
            current = nextMap;
        }

        current.put(parts[parts.length - 1], value);
    }

    @Override
    public boolean exists() {
        // Check if document has data (exists in storage)
        return !data.isEmpty();
    }

    @Override
    public Map<String, Object> toMap() {
        return new HashMap<>(data);
    }

    @Override
    public String toJson() {
        return gson.toJson(data);
    }

    // Package-private getters for internal use
    @Override
    public String getId() {
        return id;
    }

    String getCollection() {
        return collection;
    }
}

