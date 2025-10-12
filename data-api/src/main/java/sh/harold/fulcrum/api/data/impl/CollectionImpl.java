package sh.harold.fulcrum.api.data.impl;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.query.Query;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of the Collection interface.
 * Handles document operations for a specific collection.
 */
public class CollectionImpl implements Collection {

    private final String name;
    private final StorageBackend backend;

    public CollectionImpl(String name, StorageBackend backend) {
        this.name = name;
        this.backend = backend;
    }

    @Override
    public CompletableFuture<Document> selectAsync(String id) {
        return backend.getDocument(name, id)
                .handle((doc, throwable) -> {
                    if (throwable != null || doc == null) {
                        return new DocumentImpl(name, id, null, backend);
                    }
                    return doc;
                });
    }

    @Override
    public CompletableFuture<Document> createAsync(String id, Map<String, Object> data) {
        Map<String, Object> snapshot = data != null ? new HashMap<>(data) : new HashMap<>();
        return backend.saveDocument(name, id, snapshot)
                .thenApply(unused -> new DocumentImpl(name, id, snapshot, backend));
    }

    @Override
    public CompletableFuture<Boolean> deleteAsync(String id) {
        return backend.deleteDocument(name, id)
                .exceptionally(throwable -> false);
    }

    @Override
    public Query find() {
        return new QueryImpl(name, backend);
    }

    @Override
    public Query where(String path) {
        return new QueryImpl(name, backend).where(path);
    }

    @Override
    public CompletableFuture<List<Document>> allAsync() {
        return backend.getAllDocuments(name);
    }

    @Override
    public CompletableFuture<Long> countAsync() {
        return backend.count(name, null);
    }
}
